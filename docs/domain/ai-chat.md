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

## 주요 시나리오

**메시지 전송 (SSE 스트리밍)** — `AiChatMessageSendService`. 순서가 중요하다:

1. 본문 검증 (빈 값·1,000자 초과 → 400)
2. 상태 무관 USER 메시지 **10초/5건** 폭주 가드 (DB 카운트). 초과 시 429.
   초 단위 폭주(무한 retry·키 유출)만 막고, 비용 방어의 본체는 아래 토큰 예산이다.
3. 소유권·잠금 확인 — `LOCKED` 면 400(이미 감상문 확정), 활성 summary_job 이
   있으면 400(생성 중). 이 시점까지 USER 메시지는 저장되지 않는다.
4. 입력 moderation (`InputModerationClient`, SSE 시작 전 동기 호출) —
   차단이면 `REJECTED` 로 저장 후 400, moderation API 자체가 죽어 있으면
   저장 없이 503 (판정 불가 시 통과가 아니라 차단을 선택하는 정책).
5. **토큰 예산 선불 예약** (moderation 통과 후·USER 저장 전) — KST 4시간 창당 20,000 토큰
   (Redis, 선불 예약 + 실측 보정). 초과 시 429 + Retry-After, 아무것도 저장하지 않는다.
   예산은 **사용자가 보낸 메시지 입력 + 받은 응답 출력만** 계상한다(시스템 프롬프트·재전송 이력·
   요약 등 서비스 오버헤드는 미계상 — 공정성 한도). Redis 장애 시 허용(fail-open).
6. USER 메시지 `COMPLETED` 저장 → LLM 스트리밍. 출력 쪽 가드레일(프롬프트
   주입 패턴, 출력 moderation)은 도메인이 아니라 인프라의 Spring AI advisor
   체인에 있다 (`infrastructure/ai/openai/advisor/`). advisor 가 차단해도
   거부 문구가 정상 토큰으로 흘러오므로 메시지는 `COMPLETED` 로 저장된다.
7. 스트림 에러·클라이언트 중단 시 부분 응답을 `FAILED` 로 저장한다 — 버리지
   않는 이유는 관측(무엇이 어디까지 나갔는가) 때문이고, `FAILED` 는
   컨텍스트에서 제외되므로 다음 턴을 오염시키지 않는다.

영속화는 `AiChatMessagePersistService` 로 분리되어 있다. Reactor 콜백에서
`@Transactional` 이 프록시를 타게 하기 위한 분리다 (같은 클래스 내부 호출은
트랜잭션이 걸리지 않는 Spring 제약 회피).

**세션 제목 생성 (비동기)** — 첫 ASSISTANT 응답 저장 커밋 후
(`@TransactionalEventListener(AFTER_COMMIT)`) 전용 격벽 스케줄러로 넘겨
LLM 으로 제목을 만든다. 채팅 저장과 분리한 이유: 제목 생성 LLM 호출의
실패·지연이 채팅 본편을 롤백하거나 막아서는 안 되기 때문. 격벽 크기는
thread 32 / thread 당 큐 64 (docs/record/0001 참조).

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
| 컨텍스트 조립 | 토큰 예산 기반 원문 꼬리 — hard-cap 8,000 토큰(`COMPLETED` 만), newest-first 로 `token_count` 합산, 턴 경계 정렬(꼬리는 USER 시작), 마지막 턴 강제 포함. 초과분은 오래된 턴부터 제외. 요약 결합은 PR-3 |
| 토큰 계산 | jtokkit(o200k_base) 로컬 계산. `ai_chat_message.token_count` = USER 로컬 계산 · ASSISTANT 실측 출력 |
| 메시지 최대 길이 | 1,000자 |
| 폭주 가드 (DB 카운트) | 10초/5건 — 상태 무관 USER 메시지 |
| 토큰 예산 (Redis) | KST 4시간 창당 20,000 토큰, 예약 출력 512. 사용자 메시지 입력 + 받은 출력만 계상, 채팅 스트림만 |
| 전역 게이트 (Redis) | 모델별 분당 예산 — gpt-4o-mini RPM 9,000 / TPM 180,000 (계정 한도의 90%). 채팅·제목·감상문 공통, 포화 시 채팅 429 / 제목 생략 / 감상문 재큐. quota 소진 시 쿨다운(기본 300초) 열어 전 경로 차단 |
| 감상문 생성 자격 | 누적 토큰 500 이상 (`SummaryDraftPolicy` 상수, 정책 확정 전 임시값) |

## 경계

**이 도메인에 속하는 것**: 세션·메시지 생명주기, 컨텍스트 윈도우 정책,
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

- `domain/aiChat/` — service(11개) · service/policy · out(포트 4) · event ·
  listener · config · dto
- `model/aiChat/` — AiChatSession, AiChatMessage
- `infrastructure/ai/openai/` — 포트 구현체, advisor 체인
- `infrastructure/redis/` — 토큰 예산 어댑터(`ChatTokenBudgetRedisAdapter`), 창 계산기
- `infrastructure/aiChat/scheduler/` — 감상문 작업 적재 스케줄러
- `presentation/controller/aiChat/`

## 관련 기록

- docs/record/0001 — 제목 생성 스케줄러 격벽 (thread 32 / queue 64 인 이유)
- docs/record/0003 — 동시성 붕괴 처방: moderation 을 블로킹 JDK HttpClient 로
- 이슈 #92 — summary_job 적재 락 타임아웃 사고
- 이슈 #103 — 동시성 병목 조사 (OSIV off · virtual thread 의 배경)
- 이슈 #113 — rate limit 키가 프록시 IP 로 묶이던 구조 처방 (런칭일 429)
