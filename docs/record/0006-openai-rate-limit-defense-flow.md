# 0006 — OpenAI 호출 rate limit·quota 방어 설계 (전역 게이트 → 가드 → 다층 처리 흐름)

- **상태**: 채택
- **날짜**: 2026-07-07
- **관련**: 전역 게이트는 [[project_token_limit_and_context_compression]] 스펙에서 페이서를 대체해 도입. 번역 가드 공통화는 커밋 `bc12f1b`(PR #143). [0001](0001-aichat-title-generation-scheduler-bulkhead.md)·[0005](0005-context-summary-concurrency-transaction.md)의 백그라운드 워커와 연결.
- **읽는 목적**: OpenAI 호출이 rate limit/quota 에 걸릴 때 "어디서 무엇이 걸러지고, 왜 그렇게 나눠 놓았는지"를 흐름으로 이해하기 위한 설명서. 코드가 *무엇*을 하는지는 각 클래스가, 여기선 *왜 이 구조인지*를 적는다.

## 한 줄 요약

OpenAI 계정 429 를 **두 겹으로** 막는다: (1) 호출 **전** 전역 게이트가 분당 예산을 보고 미리 거절(예방), (2) 그래도 새서 OpenAI 가 실제 429 를 주면 응답 핸들러가 잡아 quota 쿨다운을 열고 재시도를 안내(안전망). 두 겹 모두 실패를 **하나의 내부 예외**(`TooManyRequestsException`)로 번역하고, 그 예외를 받는 각 호출자가 자기 방식(429 응답·재큐·무벌점 반납·스킵)으로 처리한다.

## 구성 요소와 책임

| 요소 | 계층 | 책임 | 일부러 **안** 하는 것 |
|---|---|---|---|
| `OpenAiRequestGate` | infra | 분당 예산(Redis 고정 창) 판정 → `Permitted`/`Rejected`. quota 쿨다운 진입·조회 | **정책 없음** — 거절 시 무엇을 할지 안 정함 |
| `OpenAiRateLimitGuard` | infra | 게이트 `Rejected` → `TooManyRequestsException`(QUOTA/BURST) **번역** | 토큰 추정·호출 실행 안 함 |
| `RateLimitInfo` | domain | 재시도 메타(Retry-After 등) **운반**. 출처 무관 단일 형태 | 어느 출처인지 구분 안 함 |
| `OpenAiResponseErrorHandler` | infra | OpenAI **실제 429** 분류(quota vs burst) + 쿨다운 진입 + 번역 | 예방 안 함(사후 대응) |
| `GlobalExceptionHandler` | presentation | `TooManyRequestsException` → HTTP 429 + 헤더. **단일 매핑 지점** | 도메인 분기 안 함 |
| 각 호출자(서비스·워커·리스너) | domain | 번역된 예외를 받아 **거절 후 행동** 결정 | 번역·판정 안 함 |

핵심 원칙: **판정(게이트) → 번역(가드/핸들러) → 행동(호출자)** 세 책임을 분리한다. 각 단계는 다음 단계가 무엇을 할지 모른다.

## 요청 흐름

```
[호출자] 채팅/감상문/제목/컨텍스트요약
   │  ① estimatedTokens 계산 (시스템프롬프트 + 이력 + 예약 출력 — 호출 종류마다 다름)
   ▼
[OpenAiRateLimitGuard] acquireOrThrow(model, estimatedTokens)
   │
   ▼
[OpenAiRequestGate] tryAcquire(model, estimatedTokens)
   │  quota 쿨다운 중? ──── yes ──► Rejected(QUOTA_COOLDOWN)
   │  Redis 분당 카운터 INCRBY(요청1 + 토큰) → 한도 초과? ─ yes ─► 롤백 + Rejected(RATE_BUDGET)
   │  Redis 장애/미설정 모델? ─ yes ─► Permitted (fail-open + ERROR 로그)
   │  그 외 ──► Permitted
   │
   ├── Permitted ──► 실제 OpenAI 호출
   │                    │
   │                    ▼
   │              [OpenAiResponseErrorHandler]  ◄── 예방을 뚫고 온 실제 429 안전망
   │                 429 + insufficient_quota? ─► enterQuotaCooldown + throw(QUOTA_EXHAUSTED)
   │                 429 그 외 ────────────────► throw(RATE_LIMIT_BURST)
   │
   └── Rejected ──► throw TooManyRequestsException(QUOTA_EXHAUSTED | RATE_LIMIT_BURST,
                          RateLimitInfo.retryAfterOnly(retryAfter))
                       │
                       ▼  (예방·안전망 어느 쪽이 던졌든 같은 예외 타입)
        ┌──────────────┴───────────────── 호출자별 "거절 후 행동" (여기부터 제각각) ──┐
        │ 채팅   → GlobalExceptionHandler → HTTP 429 (조립 시점) / SSE error (스트림 중) │
        │ 감상문 → SummaryGenerationWorker → quota:재큐 / burst:무벌점 반납              │
        │ 컨텍스트→ ContextSummaryWorker    → quota:재큐 / burst:무벌점 반납              │
        │ 제목   → TitleGenerationListener  → 예외 삼킴 → 이번 회차 스킵                  │
        └────────────────────────────────────────────────────────────────────────────┘
```

## 설계 근거 — 왜 이렇게 나눴나

**1) 왜 두 겹(예방 게이트 + 사후 핸들러)인가.** 게이트만으론 계정 quota 소진(계정 전역 문제)을 분당 예산으로 못 막고, 고정 창 경계 버스트가 새면 실제 429 가 올 수 있다. 그래서 사후 핸들러가 안전망으로 필요하다. 반대로 핸들러만 두면 매 요청이 429 를 받고 나서야 물러나 낭비·지연이 크다. 예방으로 대부분 걸러 내고, 새는 것만 사후에 잡는 이중 구조가 낭비와 누락을 동시에 줄인다. 이 안전망 덕분에 게이트가 완벽하지 않아도 되어, 게이트를 "정밀 회계가 아닌 근사 예방"으로 단순하게 둘 수 있다.

**2) 왜 게이트는 판정만 하고 정책이 없나.** 거절 시 행동이 호출자마다 완전히 다르다(429 / 재큐 / 반납 / 스킵). 게이트가 "거절이면 429" 같은 특정 행동을 품으면 워커·리스너 경로가 못 쓴다. 그래서 게이트는 `Permitted/Rejected` 라는 중립 언어로만 말하고, 무엇을 할지는 바깥이 정한다. 덕분에 게이트 하나를 4경로가 공유한다.

**3) 왜 "번역"과 "행동"을 다른 곳에 두나.** 거절 처리가 두 층으로 나뉜다. 층 1 = `Rejected` → ErrorCode 판별 → `TooManyRequestsException` 던지기(4경로 **동일**). 층 2 = 그 예외로 무엇을 하나(경로마다 **다름**). 층 1 은 순수 번역이라 `OpenAiRateLimitGuard` 한 곳이 소유해 중복·불일치를 없애고(예전엔 4곳에 글자까지 복제됐다), 층 2 는 다양성이 본질이라 각 호출자에 남긴다. 이 분리로 "매핑을 바꿀 때 4곳을 다 고쳐야 하고 한 곳 빠뜨리면 조용히 어긋난다"가 사라진다.

**4) 왜 토큰 추정은 호출자에 남기나.** `estimatedTokens` 는 시스템 프롬프트 + 이력 + **예약 출력 토큰**인데, 예약 출력 상수·입력 소스가 호출 종류마다 다르다(채팅/요약/제목/컨텍스트). 가드로 끌어올리면 파라미터만 늘고 정확도는 그대로다. 그래서 "무엇을 계상할지"는 호출자, "그 값으로 통과를 확보"는 가드로 나눈다.

**5) 왜 게이트는 1분 고정 창이고, 왜 정밀하지 않아도 되나.** 목적이 정밀 회계가 아니라 계정 429 예방이라, Redis 고정 창(epoch-minute 버킷 + 분당 rpm/tpm 카운터)으로 충분하다. 사후 보정도 안 한다(분 창은 금방 지나감). 한도를 계정 실한도의 90%(9000/10000, 18만/20만)로 잡아 경계 버스트·부정확을 흡수한다. Redis 장애·미설정 모델은 **fail-open**(허용 + ERROR 로그) — 방어 실패가 서비스 중단으로 번지지 않게, 대신 조용한 통과는 막게 로그를 남긴다.

**6) 왜 `RateLimitInfo` 는 출처를 안 가리는 단일 형태인가.** 재시도 메타를 만드는 출처가 넷이다(게이트 거절 / OpenAI 429 헤더 / 사용자 토큰 예산 / 사용자 메시지 빈도). 각자 7필드 중 다른 부분집합을 채우지만 **전부 같은 두 출구**(HTTP 헤더 / SSE payload)로 나간다. 그래서 "모든 사연의 합집합" 하나로 두고 채워진 필드만 노출한다. 출처별로 쪼개면 공통 인터페이스·직렬화를 도로 만들어야 하고, OpenAI 헤더는 본질적으로 가변이라 쪼개도 null 이 안 없어진다. 게이트 거절처럼 필드가 하나뿐인 생성은 `retryAfterOnly()` 팩토리로 의도를 이름에 드러낸다.

**7) 왜 인프라가 도메인 예외를 던지나.** 기술 실패(게이트 거절·OpenAI 429)를 내부 공용 업무 예외로 번역하는 건 어댑터의 책임에 가깝다. 계층 규칙상 `infrastructure → domain` 은 정방향이라 허용이며, `GlobalExceptionHandler`(presentation)가 그 예외를 HTTP 로 바꾼다. `TooManyRequestsException` 이 도메인에 사는 이유가 "인프라가 던지고 표현계층이 받는" 공용 언어라서다.

## 곁가지 — 자체 제한도 같은 예외·같은 429 로 흐른다

사용자 대면(채팅) 경로에선 **우리 자체 제한**(`USER_RATE_LIMIT_EXCEEDED` 메시지 빈도, `USER_TOKEN_BUDGET_EXCEEDED` 토큰 예산)도 **OpenAI 측 제한**(`AI_RATE_LIMIT_BURST`/`AI_QUOTA_EXHAUSTED`)과 똑같이 `TooManyRequestsException` 을 만들고, `GlobalExceptionHandler` 의 단일 핸들러가 전부 HTTP 429 + `RateLimitInfo` 헤더로 매핑한다. 차이는 ErrorCode 메시지와 채워지는 필드뿐이다. (백그라운드 워커에선 같은 예외가 429 대신 재큐/반납이 되는데, 이는 "다른 처리"가 아니라 거기엔 HTTP 요청이 없어서다.)

## 검증

`OpenAiRequestGate` 판정은 `OpenAiRequestGateTest`, 번역(층 1)은 `OpenAiRateLimitGuardTest`(QUOTA/BURST/Permitted 3케이스), 실제 429 분류는 `OpenAiResponseErrorHandlerTest`, 거절 후 행동(층 2)은 각 워커·서비스 테스트가 덮는다.

## 인접 논의 — 아직 안 건드림 (트리거·근거만)

**A) 고정 창 → 슬라이딩 윈도우.** 고정 창의 유일한 약점은 분 경계 전후 2배 버스트다. 지금 급하지 않다: (1) 게이트는 의도적 근사, (2) 90% 헤드룸이 흡수, (3) 현재 규모(t3.micro)는 한도 근처 트래픽이 없음, (4) 뚫려도 사후 핸들러가 안전망. 다만 **버킷 설계 자체가 슬라이딩을 싸게 얹기 위한 포석**이다 — 분당 키 + TTL 2분이라 "이번 분/지난 분" 카운터가 동시에 살아 있어, 로그 방식(무겁고 Lua 필요) 대신 **슬라이딩 카운터 근사**(`이번분 + 지난분 × (1-경과비율)`)를 읽기 로직만 바꿔 얹을 수 있다. **되살릴 트리거**: 분 경계에 몰린 계정 429 가 관측되면. 그때도 가장 싼 처방은 한도 % 하향(코드 0줄) → 슬라이딩 카운터 근사(+Lua, TTL 3분) → 풀 로그(최후).

**B) 제목의 durable 워커화 + 비동기 잡 저장소.** 제목 생성은 "저빈도·유실 허용" fire-and-forget 스킵으로 의도됨(가치가 낮아 미뤄둔 것). durable 하게 만든다면: 외부 큐는 이 규모에선 "확장성 설계"보다 이른 인프라(dual-write→outbox·멱등 부담). DB-as-queue 는 "상태 변경 + enqueue"를 한 트랜잭션에 묶어 정합성이 공짜고, 잡을 일급 도메인 레코드로 다루는 이 코드베이스와 맞다. 테이블은 **타입별**로 두되(강타입·FK·락 경합 격리 — [[project_summary_job_lock_incident]]) 공유 **잡 골격**(claim-with-SKIP-LOCKED, 재시도, `recordFailure`/`releaseWithoutPenalty`, 백오프)을 추상화해 "매번 테이블" 부담을 없앤다. 이 골격 추상화는 speculative 가 아니라 `SummaryGenerationWorker`·`ContextSummaryWorker` 의 `handleRateLimited` 가 이미 판박이로 중복이라는 현존 근거가 있다.

## 근거 자료

- 게이트 설계 의도(정책 없음·근사·fail-open): `OpenAiRequestGate` 클래스 주석.
- 전역 게이트 도입 맥락: [[project_token_limit_and_context_compression]] 스펙(로컬 `docs/superpowers/specs`, gitignore).
