# AI 채팅 (aiChat)

## 책임

사용자가 내 책장에 등록한 책(userBook) 한 권에 대해 AI 와 나누는 대화 —
세션과 메시지의 생명주기를 관리한다. 대화의 산출물인 감상문에 대해서는
**사용자 대면 부분만** 담당한다: 생성 요청 접수, 생성 자격 판정, 완성본 편집.
생성 실행(작업 큐·워커)은 summary 도메인 소관이다.

## 핵심 엔티티와 상태

엔티티는 2개다. Summary·SummaryJob 은 `model/summary/` 소속이며 이 도메인
엔티티가 아니다.

**AiChatSession** — 상태는 `ACTIVE` → `LOCKED` 단방향.

- `LOCKED` 는 "감상문이 확정된 세션의 영구 종료"다. 되돌리는 메서드가 없고,
  `lock()` 을 호출하는 곳은 summary 도메인의
  `SummaryJobLifecycleService.recordSuccess()` 한 곳뿐이다 — 감상문 저장과
  같은 트랜잭션에서 잠근다.
- "감상문 생성 중" 은 세션 상태가 아니다. `summary_job` 활성 행의 존재로
  표현되며, 화면에 보여주는 ACTIVE / SUMMARIZING / SUMMARIZED 는 조회 시점에
  JPQL CASE 로 합성하는 표시용 상태다 (`AiChatSessionDisplayStatus`).
- `accumulatedTokens` 는 ASSISTANT 출력 토큰만 누적한다. 입력 프롬프트는 매
  턴 컨텍스트가 중복 전송되어 대화량 지표로 부적합하기 때문.

**AiChatMessage** — 생성 후 불변. 상태는 생성 시점에 확정되고 전이가 없다.

| Status | 의미 | 컨텍스트 윈도우 포함 |
|---|---|---|
| `COMPLETED` | 정상 USER 메시지, 정상 종료 ASSISTANT 응답 | O |
| `FAILED` | 스트림 비정상 종료 시 부분 응답 보존용 (ASSISTANT) | X |
| `REJECTED` | 입력 가드레일이 차단한 USER 메시지 — 감사 추적용으로만 저장 | X (모든 LLM 프롬프트에서 제외) |

## 용어 (컨텍스트 압축)

긴 대화를 압축할 때 쓰는 핵심 용어. 코드·문서·대화에서 이 말로 통일한다.

| 용어 | 코드 식별자 | 뜻 |
|---|---|---|
| **요약 반영 지점** | `summarizedUpToMessageId` (`summarized_up_to_message_id`) | 대화를 어디까지 누적 요약에 반영했는지 가리키는 메시지 id. 이 id 이하는 요약이 커버하고, 초과는 아직 원문. 항상 완결된 턴의 끝(ASSISTANT). |
| **최근 원문 대화** | `recentMessages` | 요약 반영 지점 이후, 압축하지 않고 원문 그대로 채팅에 싣는 최근 메시지들. 항상 USER 로 시작. |
| **요약 대상 원문** | `messagesToSummarize` | 이번 요약 작업이 새로 압축해 요약에 합칠, 요약 반영 지점 이후 원문 묶음(최근 원문 대화로 남길 최신분은 제외). |

> 쓰지 않는 말: `boundary`(경계), `tail`(원문 꼬리), `delta`. 자료구조가 아니라 업무 의미로 이름 짓는다 — [conventions/naming.md](../conventions/naming.md).

## 주요 시나리오

**메시지 전송 (SSE 응답)** — `AiChatMessageSendService`. 두 단계로 나뉜다:
사전 단계 `prepare()`(요청 스레드, 동기 — 예외는 HTTP 4xx/5xx JSON)와
생성 단계 `generateAndPersist()`(가상 스레드, SSE 시작 후 — 실패는 SSE error 이벤트).
사전 단계의 순서가 중요하다:

1. 본문 검증 (빈 값·1,000자 초과 → 400)
2. 메시지 전송 시도 **10초/5건** 폭주 가드 (Redis ZSET + Lua — 검사와 기록을 원자로
   수행해 동시 요청이 같이 통과하지 못한다). 초과 시 429. 슬롯은 검사 시점에 소모되고
   뒤 단계(모더레이션 차단·예산 거절 등)에서 거절돼도 반환하지 않는다 — 시도 자체를 센다.
   Redis 장애 시 검사 없이 허용(fail-open).
   초 단위 폭주(무한 retry·키 유출)만 막고, 비용 방어의 본체는 아래 토큰 예산이다.
3. **토큰 예산 선불 예약** — KST 달력 하루당 120,000 토큰 (DB 원장 `user_token_budget`,
   사용자·일 1행, "사용량 + 예약량 ≤ 한도" 조건부 원자 UPDATE 한 문장으로 예약과 한도
   검사를 동시에). 초과 시 429 + Retry-After(다음 KST 자정까지), 아무것도 저장하지 않는다.
   예약을 moderation 앞에 두는 이유: 예산이 소진된 사용자가 공짜 moderation 호출
   (제공자 RPM 자원)을 소모하지 못하게 한다.
   예산은 **사용자가 보낸 메시지 입력 + 받은 응답 출력만** 계상한다(시스템 프롬프트·재전송 이력·
   요약 등 서비스 오버헤드는 미계상 — 공정성 한도). DB 가 원장 정본이라 우회(fail-open)가
   없다 — DB 장애면 채팅 요청 자체가 실패한다. 예약 이후 단계에서 거절·실패하면
   (잠금·moderation 차단/불능·게이트 거절·저장 실패) 예약을 전액 환불한다.
4. 소유권·잠금 확인 — `LOCKED` 면 400(이미 감상문 확정), 활성 summary_job 이
   있으면 400(생성 중). 이 시점까지 USER 메시지는 저장되지 않는다.
5. 입력 moderation (`InputModerationClient`, SSE 시작 전 동기 호출) —
   차단이면 `REJECTED` 로 저장 후 400, moderation API 자체가 죽어 있으면
   저장 없이 503 (판정 불가 시 통과가 아니라 차단을 선택하는 정책).
6. **OpenAI 전역 게이트 확보**(`acquireRateLimitPermit`) → USER 메시지 `COMPLETED` 저장.
   게이트 거절이면 예약을 전액 환불하고 429 — SSE 시작 전이라 HTTP JSON 으로 나가고,
   USER 저장 전이라 응답 없는 USER 메시지가 대화 이력에 남지 않는다.

생성 단계: LLM 을 **비스트리밍 동기 호출**(`ChatClient.call()`, JDK HttpClient,
read 타임아웃 90초·자체 재시도 없음)로 부르고, 완성 응답을 token 이벤트 1건 + done 으로
SSE 방출한다. 출력 쪽 가드레일(프롬프트 주입 패턴, 출력 moderation)은 도메인이 아니라
인프라의 Spring AI advisor 체인에 있고 호출 스레드에서 동기로 실행된다. advisor 가
차단해도 거부 문구가 정상 응답으로 돌아오므로 메시지는 `COMPLETED` 로 저장된다.
실패 처리: 생성 실패는 빈 본문 `FAILED` + error 이벤트, 저장 실패는 생성된 본문을
보존한 `FAILED` + error 이벤트. 생성 실패면 전역 게이트의 분당 계상도 보상 차감한다
(확보 시점의 분 키 기준). 실패의 대부분(연결 실패·4xx·429)은 OpenAI 가 토큰을 소모하지 않아
보상이 실제와 맞지만, HTTP 200 을 받은 뒤 응답을 읽거나 변환하다 실패한 경우는 이미 과금된 뒤라
실제보다 많이 되돌리는 셈이 된다. 그래도 현재 분 예산이 부풀지는 않는다 — 보상은 확보 당시의 분 키를
되돌리므로, 분을 넘겨 도착한 실패(읽기 타임아웃 90초가 대표 사례)는 이미 지나간 창을 건드린다.
반대로 생성 성공 후 저장 실패는 토큰이 실제로 소모돼 계상을 그대로 둔다. 클라이언트가 도중에 이탈해도 끝까지 생성·저장(`COMPLETED`)
하고 예산도 실측 계상한다 — 이탈을 감지할 스트림이 없고, 돌아온 사용자가 이력에서
답을 볼 수 있는 편이 낫다. `FAILED` 는 컨텍스트에서 제외되므로 다음 턴을 오염시키지 않는다.
성공 정산은 저장된 ASSISTANT 메시지 id 를 멱등 키로 1회만 기록된다
(`ai_chat_token_settlement` 의 UNIQUE 가 이중 정산을 구조적으로 차단).

영속화는 `AiChatMessagePersistService` 로 분리되어 있다. 생성 단계가 요청 트랜잭션
밖(가상 스레드)에서 실행되므로 `@Transactional` 이 프록시를 타게 하기 위한 분리다
(같은 클래스 내부 호출은 트랜잭션이 걸리지 않는 Spring 제약 회피).

**세션 제목 생성 (비동기)** — 첫 ASSISTANT 응답 저장 커밋 후
(`@TransactionalEventListener(AFTER_COMMIT)`) 공용 가상 스레드 executor
(`aiChatVirtualThreadExecutor`)로 넘겨 LLM 으로 제목을 만든다. 채팅 저장과 분리한 이유:
제목 생성 LLM 호출의 실패·지연이 채팅 본편을 롤백하거나 막아서는 안 되기 때문.
(과거의 전용 격벽 스케줄러는 가상 스레드 전환으로 제거 — 스레드가 희소 자원이 아니게
되어 격벽의 전제가 사라졌다. 격벽 시절 배경은 docs/record/0001 참조.)

**컨텍스트 요약 (비동기)** — 긴 대화의 호출당 입력 토큰을 유계로 만들기 위해
과거 원문을 누적 요약으로 대체한다. ASSISTANT 응답 저장 커밋 후
(`@TransactionalEventListener(AFTER_COMMIT)`) "요약 반영 지점 이후 최근 원문 대화
토큰 합 > 4,000" 이면 요약 job(`ai_chat_context_summary_job`)을 멱등 적재한다
(세션당 활성 1개 unique). 전용 워커(`ContextSummaryWorker`)가 감상문 큐
골격(SKIP LOCKED·lease·재시도·reaper)을 복제해 처리한다 — 이전 요약 +
요약 반영 지점 이후 원문을 LLM(전역 게이트 경유)으로 합쳐 새 누적 요약을 만들고,
`ai_chat_context_summary` 를 **`version` 낙관적 갱신 + 요약 반영 지점 단조 증가** 로만
반영한다(늦게 돌아온 옛 워커의 덮어쓰기 차단). 요약 범위는 처리 시점에
계산한다: 현재 요약 반영 지점부터, 최신 원문 중 2,000 토큰을 남긴 지점(가장 가까운
턴 경계로 내림)까지. 요약 대상 원문이 한 호출 예산(maxRequestTokens)을 넘으면
오래된 쪽부터 잘라 여러 작업이 나눠 소화한다 — 요약 반영 지점이 늘 전진해 영구 동결이 없다.
감상문 큐와 공통 추상화로 묶지 않는다 — 두 큐의
생명주기가 다르다(요약은 세션 잠금 없이 반복 갱신). 요약 실패는 채팅에
무영향이다: 조립기가 최근 원문 최대 토큰(8,000) 안에서 원문으로 계속 동작하고, 계속
밀리면 오래된 쪽부터 잘리는 우아한 열화 + 로그.

**감상문 흐름 (경계에 걸친 시나리오)** — 시작은 둘 중 하나:

- 수동: 사용자가 요청 → `SummaryDraftService` 가 자격 판정(누적 토큰 500
  이상, 이미 잠긴 세션 거부) 후 **SummaryJob 을 적재만** 한다 (409 = 이미
  진행 중).
- 자동: 매일 06:00 `SummaryScheduler` 가 자격 있는 세션(ACTIVE + 토큰 500 +
  24시간 내 대화)의 작업을 집합 단위로 적재한다.

이후는 summary 도메인: 워커가 작업을 선점해 `AiSummaryClient` 로 생성하고,
성공 시 Summary 1행 저장 + 세션 `lock()`. 완성본의 제목·본문 편집만 다시
aiChat 의 `SummaryEditService` 가 담당한다 (LLM 무관, 사용자 직접 수정).

## 정책 값

`domain/aiChat/config/AiChatProperties` (prefix `ai-chat`). 현재 값:

| 항목 | 값 |
|---|---|
| 컨텍스트 조립 | `[시스템+책][누적 요약][요약 반영 지점 이후 최근 원문 대화][현재 메시지]`. 요약 반영 지점(`summarized_up_to_message_id`) 이후 `COMPLETED` 원문만 최근 원문 대화로, newest-first 로 `token_count` 합산해 최근 원문 최대 토큰 8,000(`assembly-recent-raw-max-tokens`, 안전핀)까지, 턴 경계 정렬(최근 원문 대화는 USER 시작), 마지막 턴 강제 포함. 요약 반영 지점 앞은 누적 요약이 대체 — 중복도 구멍도 없다. 요약은 시스템 프롬프트 뒤 저변동 블록으로 붙고 사용자 예산엔 미계상 |
| 컨텍스트 요약 | ASSISTANT 응답 커밋 후 "요약 반영 지점 이후 최근 원문 대화 토큰 합 > 4,000" 이면 요약 job 적재(멱등). 워커가 비동기로 이전 요약 + 요약 반영 지점 이후 원문 → 새 누적 요약(전역 게이트 경유, 사용자 예산 미계상). 최신 2,000 토큰(`keep-recent-raw-tokens`)은 항상 원문으로 남긴다. 요약 실패는 채팅 무영향(최근 원문 최대 토큰 안에서 원문으로 우아한 열화) |
| 계약(불변식) | `assembly-recent-raw-max-tokens`(조립 시 실을 최근 원문 최대, 8,000) ≥ `keep-recent-raw-tokens`(요약 시 남길 최근 원문, 2,000). 조립 최대가 남길 양보다 작으면 워커가 남긴 원문을 조립기가 다 싣지 못해 오래된 턴이 조용히 누락된다 |
| 토큰 계산 | jtokkit(o200k_base) 로컬 계산. `ai_chat_message.token_count` = USER 로컬 계산 · ASSISTANT 실측 출력 |
| 메시지 최대 길이 | 1,000자 |
| 폭주 가드 (Redis ZSET + Lua) | 10초/5건 — 메시지 전송 시도, 검사 시점에 슬롯 소모 |
| 토큰 예산 (DB 원장) | KST 달력 하루당 120,000 토큰(잠정 — 기존 4시간/20,000 의 산술 등가, 재산정 예정), 예약 출력 512. `user_token_budget` 에 선불 예약(조건부 원자 UPDATE) + 실측 정산, 정산은 `ai_chat_token_settlement` 로 메시지당 1회 멱등. 사용자 메시지 입력 + 받은 출력만 계상, 채팅 스트림만 |
| 전역 게이트 (Redis) | 모델별 분당 예산 — gpt-4o-mini RPM 9,000 / TPM 180,000 (계정 한도의 90%). 채팅·제목·감상문 공통, 포화 시 채팅 429 / 제목 생략 / 감상문 재큐. quota 소진 시 쿨다운(기본 300초) 열어 전 경로 차단. 채팅 생성 실패 시 확보했던 분당 계상을 보상 차감(채팅 경로만 — 성공 시 실측 재보정은 하지 않음) |
| 감상문 생성 자격 | 누적 토큰 500 이상 (`SummaryDraftPolicy` 상수, 정책 확정 전 임시값) |

## 경계

**이 도메인에 속하는 것**: 세션·메시지 생명주기, 컨텍스트 조립·요약 정책,
감상문의 사용자 대면 3종(요청 접수 `SummaryDraftService` · 자격/진행률 조회
`SummaryDraftSearchService` · 완성본 편집 `SummaryEditService`).

**속하지 않는 것**: 감상문 생성 실행(작업 큐 적재의 멱등 처리·워커·수명 관리
— summary 도메인), LLM 호출 구현과 가드레일 advisor (infrastructure).

**배치 근거**: 감상문 요청·진행률·편집은 채팅 화면의 UX 이고 세션 문맥(자격
판정이 세션의 누적 토큰·상태에 의존)에 붙어 있어 aiChat 소속이다. 생성
파이프라인은 사용자 요청과 분리된 백그라운드 작업이라 summary 소속이다.

**알려진 어긋남 (Epic #117 이관 심사에서 판정할 것 — 이 문서는 현 상태 기록)**:

- `AiSummaryClient` 포트가 `domain/aiChat/out/` 에 있는데 유일한 사용자는
  summary 도메인의 워커다.
- `SummaryEditService` 가 aiChat 패키지에서 `SummaryRepository` 를 직접
  수정한다 (Summary 엔티티는 summary 도메인 소유).
- 같은 파이프라인의 양 끝이 다른 패키지에 있다: 작업 적재 스케줄러는
  `infrastructure/aiChat/scheduler/`, 실행·회수는
  `infrastructure/summary/scheduler/`.
- 두 도메인은 상호 참조 관계다 (aiChat → SummaryJobRepository 로 잠금 판정,
  summary → 세션·메시지·포트 참조).

## 관련 패키지

- `domain/aiChat/` — service(컨텍스트 요약 워커·생명주기·적재 포함) · service/policy ·
  out(포트: `AiChatClient` · `AiSummaryClient` · `TokenCounter` · `AiContextSummaryClient` 등) ·
  event · listener(제목 생성 · 컨텍스트 요약 트리거) · config · dto
- `model/aiChat/` — AiChatSession, AiChatMessage, AiChatContextSummary, AiChatContextSummaryJob,
  UserTokenBudget(토큰 예산 일 단위 원장), AiChatTokenSettlement(정산 멱등 기록)
- `infrastructure/ai/openai/` — 포트 구현체, 기능별 하위 패키지(chat · title · summary ·
  contextsummary · guardrail · moderation · ratelimit) + 공용 배관은 루트
- `infrastructure/redis/` — 폭주 가드 어댑터(`UserMessageRateLimiterRedisAdapter`)
- `infrastructure/aiChat/scheduler/` — 감상문 작업 적재 스케줄러 + 컨텍스트 요약 디스패처·reaper·풀
- `presentation/controller/aiChat/`

## 관련 기록

- docs/record/0001 — 제목 생성 스케줄러 격벽 (thread 32 / queue 64 인 이유)
- docs/record/0003 — 동시성 붕괴 처방: moderation 을 블로킹 JDK HttpClient 로
- 이슈 #92 — summary_job 적재 락 타임아웃 사고
- 이슈 #103 — 동시성 병목 조사 (OSIV off · virtual thread 의 배경)
- 이슈 #113 — rate limit 키가 프록시 IP 로 묶이던 구조 처방 (런칭일 429)
