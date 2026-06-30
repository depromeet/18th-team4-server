# 0001 — 제목 생성 전용 스케줄러 분리 (격벽)

- **상태**: 채택
- **날짜**: 2026-06-30
- **관련**: 이슈 #103(AI 채팅 동시성 개선)의 하위 작업 — 제목 생성 동시성/안정화
- **바뀐 코드**: `AiChatSchedulerConfig`(신규 — 전용 스케줄러), `AiChatTitleClientConfig`(신규 — 전용 ChatClient + 10초 responseTimeout), `AiChatProperties.TitleGeneration`(신규), `FirstAssistantResponseCompletedEvent`·`AiChatTitleGenerationListener`(신규 — 도메인 이벤트 + AFTER_COMMIT 리스너), `AiChatMessagePersistService`(이벤트 발행 + 제목 입력=유저 첫 질문), `AiChatTitleClientImpl`(전용 ChatClient 사용), `AiChatMessageRepository.findFirstUserMessage`(신규), `application.yml`(`ai-chat.title-generation`)

## 한 줄 요약

세션 제목 생성을 **전역 `boundedElastic` 에서 떼어 전용 스케줄러로 격리**한다. 첫 메시지가 몰릴 때
느린 제목 생성 LLM 호출이 영속화 스레드를 빼앗아 사용자 응답(`Done` 이벤트)을 지연시키는 것을 막는다.

## 배경

AI 채팅 동시성 부하를 조사하던 중(#103), 채팅 한 건이 끝날 때 일어나는 두 종류의 작업이
**같은 스레드풀(`Schedulers.boundedElastic()`)** 을 공유한다는 점이 드러났다.

| 작업 | 성격 | 스레드 점유 시간 | 사용자 체감 |
|---|---|---|---|
| 영속화 (`saveAssistantSuccess` 등) | 짧은 JDBC 저장 | 수 ms | **민감** — 이게 끝나야 `Done` 이벤트가 나가 스트림이 끝맺음 |
| 제목 생성 (첫 교환에서만) | LLM 호출 + 짧은 저장 | 수 초 | 둔감 — 백그라운드, 제목은 잠시 뒤 갱신돼도 무방 |

`boundedElastic` 은 블로킹 작업을 태우는 reactor 의 스케줄러로, 기본 스레드 상한이 `10 × vCPU` 다
(2 vCPU 박스 = **20개**). 두 작업이 이 20개를 함께 쓴다.

## 문제 — 굶김(starvation)

첫 메시지가 동시에 몰려 제목 생성이 한꺼번에 트리거되면, 느린 쪽(제목 생성)이 풀을 점유해
빠른 쪽(영속화)이 스레드를 못 받고 큐에서 대기한다. 구체적 흐름:

```
t=0    boundedElastic 20슬롯 ← 제목 생성 20개가 전부 점유 (각자 LLM 호출 ~수초 진행 중)
t=0.1  다른 채팅들의 스트림이 끝남 → saveAssistantSuccess 들이 실행할 스레드 필요
       그런데 20슬롯이 다 찼음 → 영속화가 큐에서 '대기'
t=수초  제목 생성이 끝나며 슬롯 반납 → 그제서야 영속화 실행
```

영속화 자체는 ms 짜리인데, 앞선 느린 제목 생성에 밀려 수 초 기다린다. 그동안 사용자는
응답 토큰을 다 받고도 **`Done` 이벤트가 안 와서 스트림이 끝맺지 못하는** 상태로 보인다.
빠른 사용자-경로 작업이 느린 백그라운드 작업에 스레드를 빼앗기는 것 — 이것이 굶김이다.

## 의사결정 흐름

이 구조에 이르기까지의 선택을 순서대로 적는다.

**1) 제목 생성은 왜 비동기인가** — `afterCommit` 콜백은 영속화를 실행한 스레드에서 *동기로* 돈다.
거기서 제목 생성 LLM 호출(수 초)을 그냥 부르면 그 스레드가 수 초 막히고, 사용자에게 보낼 `Done`
이벤트가 그만큼 늦어진다. 사용자는 보지도 않는 제목 생성을 기다리게 된다. 그래서 별도 스레드로
던지고 결과를 기다리지 않는다(fire-and-forget). 또한 `afterCommit`(커밋 *후*)이라 제목 생성이
실패해도 ASSISTANT 메시지 저장이 롤백되지 않는다.

**2) 그 별도 스레드는 왜 `boundedElastic` 계열인가** — 제목 생성은 LLM HTTP 호출 + JDBC 저장,
즉 **블로킹 작업**이다. reactor 에서 블로킹은 `boundedElastic` 계열에서만 돌려야 한다.
이벤트루프(`reactor-http-nio`)에서 돌리면 그 루프가 멈춰 다른 스트림까지 막히고, `parallel`
스케줄러(CPU 코어 수만큼, 논블로킹 전용)에서 돌리면 코어를 막아 전체가 마비된다.

**3) 그런데 전역 `boundedElastic` 을 공유하면 문제** — 위 "굶김" 그대로다. 블로킹 스케줄러를
쓰는 것 자체는 맞지만, **영속화와 같은 풀**을 쓰는 게 화근이다.

**4) 그래서 전용 풀로 칸막이를 친다(격벽, bulkhead)** — 제목 생성만 자기 전용 스케줄러에 태운다.
제목 생성이 아무리 몰려도 자기 풀 안에서만 경합하고, 영속화 풀(전역 `boundedElastic`)에는 손대지
않으므로 사용자 응답 latency 가 보호된다. (배의 격벽처럼 한 칸이 잠겨도 다른 칸으로 안 번지게.)

**5) 전용 풀 크기 — `threadCap 32` / `queueCap 64` (전역 backlog ≈ 2,048).**

처음엔 "작은 threadCap(4)" 을 검토했다. 근거는 "제목 생성이 OpenAI 연결·DB 커넥션을 빌려 쓰니
작게 잡아 포그라운드 채팅을 보호" 였다. 그러나 두 가지를 따져보니 작게 잡을 이유가 약했다:

- **DB 커넥션은 찰나만 잡는다**: 제목 생성은 트랜잭션을 쪼개 LLM 호출을 트랜잭션 *밖* 에서 한다
  (`AiChatSessionTitleService`). 그래서 작업 시간(~수초)의 대부분인 LLM 호출 구간엔 Hikari 커넥션을
  안 쥐고, 저장하는 찰나에만 ms 단위로 빌린다. → "Hikari 보호" 는 threadCap 을 작게 둘 근거가 못 된다.
- **OpenAI 연결도 경쟁하지 않는다**: 제목 생성은 `.call()`(전용 RestClient)을 쓰고 채팅 스트리밍은
  `.stream()`(별도 WebClient 풀)을 쓴다. 경로가 달라 풀을 공유하지 않는다(공유는 OpenAI 계정 한도뿐인데
  10,000 RPM 이라 무관).

threadCap 이 실제로 제한하던 건 Hikari 가 아니라 **장애 시 blast-radius**(제목 호출이 매달릴 때 묶이는
스레드·연결 수)였다. 이 blast-radius 는 **시간으로**(전용 ChatClient 의 read 타임아웃, 아래 "추가 결정" 참고)
막는다. 그래서 풀 크기는 **threadCap 32 / queueCap 64** 로 정했다:

- **`threadCap` 32**: 동시 제목 생성 수의 상한. 스레드는 lazy 생성 + 60초 유휴 회수라 평소엔 0 에 수렴한다.
  제목 생성은 저빈도(세션당 1회)라 도착률 ≪ 처리량이므로 32 면 충분하다. 매달린 호출은 10초 responseTimeout
  이 끊어 주므로 스레드가 무한정 쌓이지 않는다.
- **`queueCap` 64**: reactor `newBoundedElastic` 에서 이 값은 **backing thread 1개당 큐 한도(per-thread)** 다.
  따라서 **전역 backlog 상한 = threadCap × queueCap = 32 × 64 ≈ 2,048**. 이 한도를 넘는 제출은
  `RejectedExecutionException` 으로 거절(로그 후 스킵) — 백그라운드라 감내 가능.

> 주의(리뷰 반영): `queueCap` 은 전역 큐 상한이 *아니다*. 초기엔 100/2000 으로 두고 "전역 2,000" 으로 오해했으나,
> reactor 소스(`maxTaskQueuedPerThread`, executor 자기 큐에 대한 `ensureQueueCapacity`)상 per-thread 이므로
> 그 값이면 전역 상한이 100×2000=200,000 까지 늘어 보호가 의도보다 100배 늦게 작동한다. 전역 한도를 의도대로
> 두려면 **threadCap × queueCap 의 곱**으로 산정해야 한다 → 32×64.

## 고려한 대안

| 대안 | 버린 이유 |
|---|---|
| 전역 `boundedElastic` 크기만 키우기(`10×vCPU` → 더 크게) | 영속화·제목 생성이 여전히 한 풀을 공유 → 굶김의 *발생 확률* 만 낮출 뿐, 버스트가 크면 같은 문제. 그리고 전역 풀은 다른 블로킹 작업도 쓰므로 부작용 범위가 넓다. |
| 제목 생성을 `parallel` 스케줄러로 | `parallel` 은 논블로킹 CPU 전용. 블로킹 LLM 호출을 태우면 CPU 코어를 막아 시스템 전체가 마비된다. |
| 제목 생성을 완전 논블로킹 reactive 체인으로 재작성 | 스레드 격리 문제를 푸는 데 과한 변경. 지금 필요한 건 "빠른 작업이 느린 작업에 안 밀리게"이고, 그건 풀 분리로 충분하다. |
| 그대로 두기(공유 유지) | 단일 인스턴스에서 첫 메시지 버스트 시 사용자 응답 지연이 실제로 발생 가능. 비용 대비 격벽이 싸고 효과가 분명하다. |

## 같은 PR 의 추가 결정

격벽(스케줄러 분리)과 함께 같은 PR 에 넣은 제목 생성 관련 결정들.

**A) 제목 입력 = 유저의 첫 질문만** — 기존엔 첫 교환 전체(USER + ASSISTANT)를 제목 프롬프트에 넣었으나,
유저의 첫 질문(첫 COMPLETED USER 메시지)만 넣도록 바꿨다. 제목은 사용자가 무엇을 물었는지를 요약하면
충분하고, 긴 답변을 함께 넣으면 주제가 희석된다. 이는 `AiChatSessionTitleService` javadoc 이 원래
의도했던 동작("첫 USER 메시지를 그대로 전달")과도 일치한다. (`AiChatMessageRepository.findFirstUserMessage`
추가 — REJECTED 는 제외되므로 차단된 입력이 먼저 있었어도 첫 *정상* 질문이 잡힌다.)

**B) 제목 생성 전용 ChatClient + 10초 read 타임아웃** — 제목 생성은 채팅과 같은 공유 ChatClient 의
`.call()` 을 쓰는데, 이 경로엔 read 타임아웃이 없었다(매달리면 무한 대기). "제목 생성만" 타임아웃을 주려면
전용 HTTP 클라이언트가 필요하다(reactor `.timeout()` 을 블로킹 호출에 걸어봐야 소켓이 안 풀린다 →
HTTP 클라이언트 레벨이어야 함). 그래서 `AiChatTitleClientConfig` 에 전용 ChatClient 를 만들어
reactor-netty `responseTimeout(10초)` 를 걸었다(moderation 이 자기 전용 RestClient 를 갖는 것과 동일 패턴).
이 타임아웃이 위 `threadCap=32` 의 매달린 호출을 시간으로 끊어 blast-radius 를 가둔다. 전용 ChatClient 엔 advisor 를 태우지 않는다 —
입력은 이미 채팅 전송 시 moderation 을 통과했고, 출력 moderation advisor 는 제목마다 호출을 더해 비용·지연만 는다.

**C) 트리거 조건 = "moderation 통과 + 성공 응답" 일 때만 (코드 변경 없이 보장 확인)** — 제목 생성은
첫 ASSISTANT 응답이 성공 저장될 때만 트리거된다. moderation 에 차단된 입력은 REJECTED 로 저장되고
예외로 빠져 스트림 자체가 시작되지 않으며(턴 카운트도 안 오름), `saveAssistantFailed` 는 제목을 트리거하지
않는다. 즉 "첫 메시지가 저장만 되면" 이 아니라 "첫 교환이 성공적으로 끝나면" 트리거된다.

**D) 트리거를 `@TransactionalEventListener(AFTER_COMMIT)` 로 (TransactionSynchronizationManager 직접 등록 대신)** —
원래는 `AiChatMessagePersistService` 가 `TransactionSynchronizationManager` 에 afterCommit 콜백을 직접 등록했다.
이를 도메인 이벤트(`FirstAssistantResponseCompletedEvent`) 발행 + `@TransactionalEventListener(AFTER_COMMIT)`
리스너로 바꿨다. 효과:
- **결합 분리**: 영속화 서비스는 "첫 응답이 끝났다" 는 사실만 알리고(이벤트 발행), 제목 생성·스케줄러·reactor 의존이
  전부 리스너로 빠진다. 영속화 서비스에서 `Scheduler`·`Mono`·TSM 의존이 사라졌다.
- **커밋 후 실행 유지**: AFTER_COMMIT 이라 메시지 저장 커밋 후에만 발화 → 제목 생성(LLM) 실패가 이 트랜잭션을
  롤백시키지 않는다. 트랜잭션 밖(테스트/배치)에서 이벤트가 와도 기본 동작상 발화하지 않아(fallbackExecution=false)
  기존 `isSynchronizationActive()` 가드를 자연스럽게 대체한다.
- **테스트 단순화**: 영속화 테스트는 TSM 수동 init/clear 없이 "이벤트가 발행됐는가" 만 검증하고, 리스너는
  별도 단위 테스트로 검증한다. (LLM 호출 offload 는 여전히 제목 생성 전용 스케줄러로 — 리스너 안에서 수행.)

## 결과 / 영향

- **얻는 것**: 첫 메시지 버스트가 와도 영속화 풀이 보호돼 사용자 `Done` 이벤트가 늦지 않는다.
  `threadCap=32`(전역 backlog ≈ 2,048)로 저빈도 버스트를 처리하고, 10초 responseTimeout 이 매달린 호출의
  blast-radius 를 시간으로 가둔다. 제목은 유저 첫 질문 기반이라 주제가 더 또렷하다.
- **치르는 것**: 스케줄러 빈 + 전용 ChatClient 빈 + 설정 두 값이 늘었다. 전용 풀 스레드(daemon)는
  평소엔 유휴 회수(60초)로 거의 0개에 수렴하므로 상시 비용은 미미하다.
- **튜닝 여지**: `ai-chat.title-generation.thread-cap` / `queue-cap` 으로 조정 가능. responseTimeout(10초)도
  필요하면 `AiChatTitleClientConfig` 에서 조정한다.

## 후속 / 미해결

- **(가) `AiChatSessionTitleService` 의 `TransactionTemplate` 제거**: 제목 생성의 트랜잭션 분리
  (LLM 호출 동안 DB 커넥션 안 쥐기) 자체는 옳으나, 구현이 `TransactionTemplate` 이라 팀 컨벤션상
  제거 대상이다(비-트랜잭션 오케스트레이터 + 별도 빈의 선언적 `@Transactional` 로 교체). 이 작업과
  독립이므로 별도 브랜치/PR 로 분리한다.
- **#103 본류(일반 채팅 동시성)**: 이 격벽은 제목 생성(첫 교환) 경로만 다룬다. 일반 채팅 요청의 동시성
  천장(이벤트루프·moderation 동기 호출·Hikari·admission control)은 별도로 진행한다.
- **(내구성 진화 경로) 외부 메시지 큐 도입 검토**: 현재 제목 생성은 in-process 휘발성(재시작/transient 실패 시
  유실, 재시도 없음)이다 — cosmetic 보조 기능이라 의도된 선택. 만약 "제목 유실 불가" 요구가 생기거나 백그라운드
  작업 방식을 통일하고 싶으면, DB-스캔 워커(요약 `summary-job` 방식)보다 **외부 메시지 큐**(Redis Streams/SQS/
  RabbitMQ 등)가 한 단계 낫다 — 내구성·재시도를 브로커에 위임해 테이블·claim·lease·reaper 를 직접 구현하지 않고,
  제목 생성·요약을 한 메시징 인프라로 통일할 수 있다. 트레이드오프는 운영할 브로커가 하나 늘어나는 것. 이번 PR·#103
  범위 밖의 별도 작업.

## 근거 자료

- 부하 조사 상세: `docs/superpowers/aichat-concurrency-investigation.md`(임시·gitignore)
- `boundedElastic` 기본 크기(`10 × vCPU`)·큐(`100_000`, **per-thread**)·유휴 회수(60초)는 reactor-core 의
  기본값이며 `Schedulers.newBoundedElastic(threadCap, queuedTaskCap, ...)` 으로 전용 풀을 따로 만들 수 있다.
  `queuedTaskCap` 은 backing thread 1개당 한도(`maxTaskQueuedPerThread`)라 전역 backlog 상한 = threadCap × queuedTaskCap.
