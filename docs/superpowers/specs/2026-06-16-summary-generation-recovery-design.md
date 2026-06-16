# 설계: 감상문 생성 종료 모델 전환 + 생성 작업 복구 계층 (Spec 1)

- 작성일: 2026-06-16
- 상태: 설계 검토 중
- 관계: **선행 두 문서를 폐기·역전한다.**
  - [2026-06-12-resume-summarized-session-design.md](2026-06-12-resume-summarized-session-design.md) (#69 "대화 이어가기")
  - [2026-06-13-reconcile-summary-model-with-dev.md](2026-06-13-reconcile-summary-model-with-dev.md)
- 후속: 캘린더(세션 중심·마지막 채팅 일시 기준) 재설계는 **Spec 2** 로 분리한다 (이 문서 범위 밖).

---

## 1. 배경 — 두 가지를 한꺼번에 한다

이 문서는 서로 맞물린 두 변경을 하나로 묶는다.

1. **비즈니스 룰 전환(#69 역전):** "감상문 생성이 완료된 세션에서 대화를 이어갈 수 있다"(#69, 이미 dev 에 PR #75 로 병합됨)를 **폐기**한다. 새 룰은 **"감상문이 완성되면 그 세션은 잠겨(LOCKED) 더 대화할 수 없다"** 이다. 그 결과 세션과 감상문은 **1:1** 이 되고 **재요약(재생성) 경로가 사라진다.**
2. **생성 작업 복구 계층 도입:** 현재 자동 생성(`SummaryScheduler`)은 새벽 6시에 대상 세션을 찾아 그 자리에서 직접 OpenAI 를 호출한다(`CompletableFuture` 팬아웃). 이 구조는 (a) 서버가 죽으면 진행 중이던 작업이 사라지고, (b) 생성 도중 죽으면 세션이 잠긴 채 영구히 멈추며, (c) 한꺼번에 호출이 몰려 OpenAI 호출 한도(rate limit)에 걸리고, (d) 실패한 건만 골라 재시도하기 어렵다. 이를 **DB 작업 큐(`summary_job`) + 워커 + 회수기(reaper)** 로 바꿔 작업이 유실·중복·고착되지 않게 한다.

두 변경을 한 문서로 묶는 이유: **복구 워커가 "생성 성공 시 세션을 잠그는(LOCKED)" 주체**라, 종료 모델과 작업 큐는 같은 코드 흐름에서 결정된다.

이 문서는 "대규모 트래픽(하루 수만 건)을 가정한 단일 인스턴스" 를 기준으로 설계한다. 복수 인스턴스 대비는 곳곳에 **확장점**으로만 표시하고 지금 구현하지 않는다(17절).

---

## 2. 새 비즈니스 모델 — 세션 종료(LOCKED) 모델

### 2.1 세션 생애

```
ACTIVE        대화 가능. (작업이 큐에 PENDING 으로 적재만 된 상태도 여기 — 아직 대화 가능)
  │  워커가 작업을 선점(PROCESSING)하고 생성 시작
(생성 중)      대화 차단 — 단, 세션 상태가 아니라 "활성 PROCESSING 작업 존재"로 차단을 도출 (2.4)
  │
  ├─ 성공 → LOCKED   대화 영구 차단(잠김). Summary 1행 존재. 끝.
  └─ 실패 → ACTIVE   세션은 손대지 않음. 작업만 재시도/실패로. 차단 자동 해제.
```

- **세션 1개 → 감상문 최대 1개(1:1).** 잠긴 뒤 재생성이 없으므로 세션당 Summary 는 최대 한 행.
- **차단 시점은 "생성 시작" 부터다.** 작업이 큐에 적재만 됐을 때(PENDING)는 아직 `ACTIVE` 라 사용자는 대화할 수 있다. 워커가 작업을 선점해 실제 생성에 들어가는 순간부터 차단한다.
- **수정한 감상문이 날아갈 일이 없다.** 재요약이 없으므로 사용자가 편집한 감상문을 덮어쓸 경로가 없다.

### 2.2 세션 상태 enum — 둘이면 충분하다

`AiChatSession.Status` = **`{ACTIVE, LOCKED}`** (현재 dev 와 값은 같지만 **의미가 바뀐다**).

| 상태 | 의미 | 대화 |
|---|---|---|
| `ACTIVE` | 일반. 작업 미적재 / 적재만 됨 / 생성 중 / 생성 실패 후 | (생성 중 외) 가능 |
| `LOCKED` | 생성 성공 — 감상문 완성, **영구 잠김** | 영구 차단 |

> **의미 재정의 주의:** dev 의 현재 `LOCKED` 는 "생성 도중 잠깐 잠김(끝나면 `unlock()` 으로 풀림)" 이다. 새 `LOCKED` 는 **"감상문 완성 → 영구 잠김(되돌리지 않음)"** 이다. 같은 이름, 일시 → 영구로 뒤집힌다.

엔티티 메서드(의도 기준):
- `lock()` : `ACTIVE → LOCKED` (생성 **성공 시 한 번**)
- `isLocked()` : 영구 잠김 여부
- dev 의 `unlock()` 은 **삭제** — 되돌릴 일이 없다.

**핵심:** 세션은 "성공했을 때 딱 한 번 `LOCKED` 로" 바뀌는 것 말곤 손대지 않는다. 생성 중/실패/크래시 같은 일시 상태는 전부 작업(`summary_job`) 테이블이 들고 있다.

### 2.3 "생성 중 차단" 은 세션 상태가 아니라 작업에서 도출한다

별도의 `SUMMARIZING` 세션 상태를 두지 않는다. 생성 중인 작업은 이미 `summary_job.status = PROCESSING` 이므로, 차단을 거기서 도출한다.

- **차단 조건** = `세션이 LOCKED` (영구) **또는** `그 세션에 PROCESSING 작업이 있고 그 lease 가 아직 유효(locked_until > now)` (일시, 생성 중).
- `locked_until > now` 를 함께 보는 이유: 크래시로 고아가 된 PROCESSING(점유 시한 지남)은 "생성 중" 으로 보지 않아, **lease 만료 시점에 회수 워커를 기다리지 않고 차단이 즉시 풀린다.**

이 도출 방식의 이점:
- 세션 엔티티는 `{ACTIVE, LOCKED}` 그대로 단순.
- 실패·크래시 처리가 전부 작업 테이블에서 끝난다 — 세션을 되돌릴 필요가 없어 회수기가 단순해진다(10절).

대가: 메시지 전송/조회 경로에서 "유효 PROCESSING 작업이 있나?" 를 인덱스 조회로 한 번 더 확인. 사소하다(기존 목록 쿼리도 EXISTS 서브쿼리를 쓴다).

### 2.4 Summary 테이블

- 구조는 현재(`createCompleted` write-once)를 거의 그대로 유지하되, 비즈니스상 세션당 1행.
- **`ai_chat_session_id` 에 unique 제약을 (다시) 건다** — 1:1 을 스키마로 못박고, 중복 생성(아래 lease 추월 등)의 최종 안전망이 된다. (#69 reconcile 에서 제거했던 것을 되돌린다.)
- 조회는 `findByAiChatSessionId(sessionId)` 단건으로 단순화 가능.
- `Summary.edit()`(사용자 편집, 제자리 수정)는 그대로 유지.

---

## 3. #69 역전 — 바뀌는 기존 코드

| 위치 | 현재 (dev, #69) | 변경 |
|---|---|---|
| `AiChatSession.Status` | `{ACTIVE, LOCKED}` (LOCKED=일시) | `{ACTIVE, LOCKED}` (**LOCKED=영구**) |
| `AiChatSession` 메서드 | `lock()`/`unlock()`/`isLocked()` | `lock()`(성공 시 영구)/`isLocked()`. **`unlock()` 삭제** |
| `AiChatMessagePersistService` (전송 가드) | `if (session.isLocked())` 차단 | `if (session.isLocked())`(영구) **또는** `유효 PROCESSING 작업 존재`(생성 중) 면 차단. 각각 `SESSION_ALREADY_SUMMARIZED`(잠김) / `SESSION_LOCKED`(생성 중) 로 분기 |
| `SummarySearchService.findBySessionId` | `isLocked()` → 409 | 유효 PROCESSING 작업 → 409 `SUMMARY_IN_PROGRESS`; `isLocked()` 면 단건 Summary 200 |
| 목록 JPQL CASE | `LOCKED → 'SUMMARIZING'`, `EXISTS Summary → 'SUMMARIZED'` | `LOCKED → 'SUMMARIZED'`, `유효 PROCESSING 작업 EXISTS → 'SUMMARIZING'`, else `'ACTIVE'` |
| `AiChatSessionDisplayStatus` | `{ACTIVE, SUMMARIZING, SUMMARIZED}` | 값 동일(산출 근거만 바뀜) |
| `SummaryDraftPolicy.evaluate` | `LOCKED→IN_PROGRESS`, `ACTIVE→토큰체크` | `LOCKED→ALREADY_SUMMARIZED`, `유효 PROCESSING 작업→IN_PROGRESS`, `ACTIVE→토큰체크` |
| `SummaryDraftService` | `execute`(TX1 lock + `@Async`), `executeForScheduler` | **작업 큐로 전환**(4~10절). 수동 경로 = 작업 적재. `executeForScheduler` 삭제 |
| `SummaryScheduler` | 6시에 직접 생성(팬아웃) | 6시에 **작업 적재만** (6절) |
| `Summary` | `ai_chat_session_id` unique 없음 | unique 추가(1:1) |

`IneligibleReason` 에 `ALREADY_SUMMARIZED` 추가, `AiChatErrorCode` 에 `SESSION_ALREADY_SUMMARIZED`("이미 감상문이 생성되어 잠긴 세션입니다.") 추가. 429 관련 코드와 `SUMMARY_IN_PROGRESS`/`SESSION_LOCKED` 는 이미 존재 — 재사용.

> #69 의 self-invocation 결함(`@Async` 프록시 미적용)은 생성이 워커로 빠지면서 자연히 사라진다.

---

## 4. `summary_job` 테이블 — DB 작업 큐

작업의 의도("이 세션은 감상문을 만들어야 한다")를 DB 에 영속화한다. **작업은 잃으면 복구 불가하므로 반드시 영속한다**(서킷브레이커와 대비 — 11절).

위치: `model/summary/entity/SummaryJob.java`.

```
summary_job
  id                  PK
  ai_chat_session_id  대상 세션 (Long 값만; JPA 연관관계 안 둠)
  active_session_id   미완료(PENDING/PROCESSING) = ai_chat_session_id, 완료(SUCCEEDED/FAILED) = NULL
  status              PENDING | PROCESSING | SUCCEEDED | FAILED
  lock_owner          선점 시 발급한 claim token(UUID). 상태 변경 시 소유권 검증용 (5분 lease 펜싱)
  locked_until        PROCESSING 점유 만료 시각 (lease = now + 5분)
  attempt_count       시도 횟수 (백오프 + 상한 판정)
  next_attempt_at     이 시각 이후 처리 가능 (백오프를 여기 인코딩)
  last_error_code     마지막 실패 분류 (관측용)
  last_error_message  마지막 실패 사유 (관측용)
  created_at, updated_at

  UNIQUE (active_session_id)            -- "세션당 활성 작업 1개" (5절)
  INDEX  (status, next_attempt_at)      -- 처리 대상 선점 쿼리용
  INDEX  (ai_chat_session_id, status)   -- "유효 PROCESSING 작업 존재?" 차단 도출용
```

`Status` 는 엔티티 내부 enum. 정적 팩토리 `createPending(sessionId)` 만 노출. 상태 전이 메서드:
- `claim(owner, lockedUntil)` : `PENDING → PROCESSING`, `lock_owner`/`locked_until` 설정
- `markSucceeded()` : `PROCESSING → SUCCEEDED`, `active_session_id = NULL`
- `scheduleRetry(nextAttemptAt, code, message)` : `PROCESSING → PENDING`, `attempt_count++`, `lock_owner`/`locked_until` 해제, 백오프. `active_session_id` 유지
- `markFailed(code, message)` : `PROCESSING → FAILED`(상한 초과/회복 불가), `active_session_id = NULL`
- `releaseAfterOrphan()` : 회수기용. `PROCESSING → PENDING`, `lock_owner`/`locked_until` 해제 (10절)

`markSucceeded`/`scheduleRetry`/`markFailed` 는 **호출 워커의 claim token 이 `lock_owner` 와 일치할 때만** 적용한다(8절 펜싱). 불일치면 결과를 폐기한다.

> 빠진 것: `RETRY_WAITING`(백오프는 `next_attempt_at` 으로 충분), `job_type`/`processing_from/to_message_id`/`chat_summary_state`(재요약·증분 없음), 토큰 추정 컬럼(호출 직전 일시 계산 — 13절).

### 4.1 lease / 회수 정책 (결정 6·7·8)

- **lease = 5분.** 워커가 작업을 `PROCESSING` 으로 선점할 때 `locked_until = now + 5분`. 감상문 생성 LLM 호출(보통 수십 초)보다 넉넉히 길게 잡아, 살아있는 느린 워커의 작업을 회수기가 잘못 뺏지 않게 한다.
- **회수 지연 = 최대 약 5분 + 회수 워커 주기.** 크래시 시 작업이 그만큼 뒤 재처리된다. 감상문 생성은 백그라운드 작업이라 이 정도 지연은 수용.
- **heartbeat 안 둔다(결정 7).** 회수 시간을 더 줄이려 점유 갱신(heartbeat)을 둘 수 있지만 복잡도가 오른다. 5분 lease 를 충분히 넉넉한 값으로 보고 단순한 lease 기반 회수로 간다. (heartbeat 는 확장점 — 17절)
- **`lock_owner` 펜싱(결정 8).** 8절.

---

## 5. 중복 방지 — 두 개의 서로 다른 장치

| 막는 문제 | 장치 |
|---|---|
| 같은 세션에 **활성 작업이 여러 개** 생기는 것 | `active_session_id` **unique 제약** |
| 하나의 작업 행을 **여러 워커가 동시에** 집는 것 | 선점 쿼리의 `FOR UPDATE SKIP LOCKED` |
| lease 만료로 **재선점된 작업을 옛 워커가 늦게 건드리는** 것 | `lock_owner` 펜싱 (8절) |

`active_session_id` = 미완료 동안만 `ai_chat_session_id`, 완료/실패 시 `NULL`. MySQL unique 인덱스는 NULL 을 여러 개 허용하므로 활성 작업만 세션당 하나로 제한된다. 적재 시 이미 활성 작업이 있으면 unique 위반 → 새 작업 안 만들고 조용히 건너뛴다(이미 예약됨).

---

## 6. 적재기 — `SummaryScheduler` (새벽 6시)

스케줄러는 OpenAI 를 직접 호출하지 않는다. 대상 세션을 찾아 `summary_job` PENDING 행만 만든다.

- 트리거: `@Scheduled(cron = "0 0 6 * * *")`.
- 대상 선정: 현행 `findAutoSummaryTargetSessionIds(ACTIVE, MIN_ACCUMULATED_TOKENS, COMPLETED, since=now-24h)` 재사용 — 세션 `ACTIVE` + 누적 토큰 ≥ 500 + 마지막 COMPLETED 메시지 24h 이내.
  - 종료 모델 덕분에 **이미 요약된 세션은 `LOCKED` 라 `ACTIVE` 필터가 자동 제외**.
- 각 대상에 `EnqueueSummaryJobService.execute(sessionId)` 로 PENDING 작업 적재(멱등 — unique 로 중복 무해).
- 대량(수만 건) 적재는 청크 insert. 적재는 빠르고, 실제 부하는 워커가 시간에 걸쳐 분산.

> `MIN_ACCUMULATED_TOKENS = 500` 의 임시성(코드 TODO)은 이 작업 범위 밖. 그대로 둔다.

---

## 7. 워커 — 선점 → 생성 → 기록

### 7.1 디스패처 (선점·제출)

`SummaryJobDispatcher` — `@Scheduled(fixedDelay = N초)`:
1. 비어 있는 워커 용량만큼 처리 대상 작업을 선점(7.2).
2. 각 작업을 고정 크기 스레드 풀(크기 `C`)에 제출.
3. `C` 는 OpenAI 호출 한도에서 역산한 낮은 값 — 동시성을 한도에 맞춰 묶는다(가상 스레드·세마포어 불필요). 실제 호출 속도는 13절 pacing 이 다시 조인다.

평소(6시 외)엔 처리 대상이 없어 선점 쿼리가 인덱스만 훑고 즉시 빈손으로 끝난다.

### 7.2 선점 쿼리 + claim 펜싱

```
status = PENDING AND next_attempt_at <= now
ORDER BY next_attempt_at, id
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

선점한 행은 같은 트랜잭션에서 `claim(owner = 새 UUID, lockedUntil = now + 5분)` 으로 `PROCESSING` 전이. **이 UUID(claim token)를 워커가 메모리에 들고 있다가** 성공/실패 기록 시 검증에 쓴다.

- 구현: Spring Data JPA `@Lock(PESSIMISTIC_WRITE)` + `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))`(Hibernate SKIP LOCKED). **Spring Boot 4 / Hibernate 조합에서 동작 확인 필요** — 안 되면 네이티브 쿼리(`... FOR UPDATE SKIP LOCKED`). 구현 계획에서 확정.
- repository 는 자기 엔티티(`SummaryJob`)만 반환.

### 7.3 한 작업의 처리 흐름 (트랜잭션 분리, `TransactionTemplate` 미사용)

오케스트레이터(`SummaryGenerationWorker`, **트랜잭션 없음**)가 각각 `@Transactional` 인 별도 빈(`SummaryJobTxService`)의 메서드를 순서대로 호출한다. OpenAI 호출은 트랜잭션 밖.

```
[선점 TX]    7.2 — 작업 PROCESSING + lock_owner + locked_until
[준비 TX]    세션 비관적 락 조회
               ├─ 세션이 ACTIVE 아님(이미 LOCKED 등) → 작업 markSucceeded(펜싱) 후 종료
               └─ ACTIVE → 대화 이력 로드 → context 반환  (세션 상태는 안 바꿈)
[밖]         breaker 열림 확인 → outbound 한도 획득 → aiSummaryClient.generate(messages)
[성공 TX]    (펜싱 통과 시) Summary 저장 + session.lock() + job.markSucceeded()  — 한 트랜잭션
[실패 TX]    (펜싱 통과 시) 분류에 따라 job.scheduleRetry(...) | job.markFailed(...)  — 세션은 안 건드림
```

- 생성에 넣는 대화 = **세션 전체 대화**(현행 `findValidMessagesBySessionIdOrderByCreatedAtAsc`). 증분 아님.
- 준비/성공/실패 TX 를 워커와 **다른 빈**으로 분리해 자기호출 프록시 문제를 피한다.
- "생성 중 차단" 은 이 흐름 동안 작업이 `PROCESSING(유효 lease)` 이라는 사실로 자동 성립한다(2.3) — 세션 상태를 별도로 만지지 않는다.

### 7.4 수동(데모) 경로

`POST .../summary-draft` 는 그 자리에서 생성하지 않고 **작업을 적재**(스케줄러와 같은 `EnqueueSummaryJobService`). 즉시 반환. 폴링:
- 생성 중(유효 PROCESSING 작업) → `GET .../summary` 409 `SUMMARY_IN_PROGRESS`
- 성공·`LOCKED` → 200 + 감상문
- 실패 → 세션 `ACTIVE`, 감상문 없음 → 404 `SUMMARY_NOT_FOUND`(재요청하면 다시 적재)

적재 자격(`SummaryDraftPolicy`): `ACTIVE` + 토큰 ≥ 500 이어야 적재. 유효 PROCESSING 작업 → 409 `SUMMARY_IN_PROGRESS`. `LOCKED` → 409 `SESSION_ALREADY_SUMMARIZED`.

---

## 8. `lock_owner` 펜싱 — 늦게 돌아온 옛 워커 막기 (결정 8)

문제: 워커 A 가 작업을 잡았는데(lease 5분) 느려서 5분을 넘긴다 → 회수기가 작업을 `PENDING` 으로 풀고 → 워커 B 가 재선점 → 그 사이 A 가 뒤늦게 LLM 응답을 받아 결과를 쓰려 한다. A 와 B 가 같은 세션을 두 번 처리하게 된다.

장치:
- 선점 시 **그 선점만의 새 UUID(claim token)** 를 `lock_owner` 에 저장하고, 워커는 그 토큰을 메모리에 보관.
- 성공/실패 기록은 조건부 update: `WHERE id = :id AND lock_owner = :myToken AND status = PROCESSING`.
  - **1행 갱신** → 내가 여전히 소유자 → 결과 반영.
  - **0행 갱신** → lease 만료 후 누군가 재선점했거나 회수됨 → **내 결과를 폐기**(로그만), 세션·Summary 도 건드리지 않음.
- 워커 식별자(고정)가 아니라 **선점마다 새 UUID** 를 쓰는 이유: 같은 워커가 자기 lease 만료 후 우연히 재선점한 경우까지 구분.

이중 안전망: 설령 펜싱을 통과한 두 결과가 경합해도, `summary.ai_chat_session_id` unique + "성공 TX 가 세션이 아직 ACTIVE 인지 확인" 이 **감상문 중복 저장을 막는다**(헛호출 한 번 낭비될 뿐).

---

## 9. 실패 처리와 재시도(지수 백오프)

생성 실패 시 작업을 지우지 않고 분류해 처리한다.

| 분류 | 출처(이미 구현됨) | 처리 |
|---|---|---|
| 일시적 호출 한도(429 burst) | `AI_RATE_LIMIT_BURST` | `scheduleRetry` — `Retry-After` 있으면 그 시각, 없으면 지수 백오프 |
| 일시적 서버 오류(5xx) | `AI_PROVIDER_TRANSIENT` | `scheduleRetry` — 지수 백오프 |
| 한도 소진(quota) | `AI_QUOTA_EXHAUSTED` | breaker 열기(11절) + `scheduleRetry`(긴 백오프) |
| 회복 불가(기타 4xx 등) | `AI_PROVIDER_ERROR` | `markFailed`(재시도 안 함) |

- 지수 백오프: `attempt_count` 기반(1→2→4→8분 …), 상한 캡.
- **시도 상한 초과** → `markFailed`(`active_session_id = NULL`). 그 세션은 다음 날 6시 정기 실행에서 (여전히 `ACTIVE` + 조건 충족 시) 새 작업으로 다시 잡힌다. 실패가 영구히 막지 않는다.
- 모든 실패 경로는 펜싱(8절) 통과 시에만 반영. 세션은 안 건드린다(차단은 PROCESSING 종료로 자동 해제).
- 429 의미·분류는 `OpenAiResponseErrorHandler`(HTTP 429 → `TooManyRequestsException` + 분류)가 **이미 구현** — 워커가 그대로 활용.

---

## 10. 멈춘 작업 회수기 (reaper)

`SummaryJobReaper` — `@Scheduled(fixedDelay ≈ 1분)`:

- 대상: `status = PROCESSING AND locked_until < now` (점유 시한을 넘긴 고아 작업 — 워커가 죽음).
- 처리(한 트랜잭션): `releaseAfterOrphan()` → `PENDING` 복귀 + `lock_owner`/`locked_until` 해제. **세션은 안 건드린다** — 차단은 PROCESSING 의 유효 lease 가 사라지면 자동 해제되므로(2.3).
- 회수기는 **고아 복구만** 담당. 일반 재시도(9절)는 워커 실패 경로가 `PENDING + next_attempt_at` 로 처리해 디스패처가 때 되면 다시 집는다.
- `lock_owner` 를 NULL 로 비우므로, 늦게 돌아온 옛 워커의 펜싱(`lock_owner = :myToken`)은 자연히 0행 → 폐기(8절).

**크래시 시 그 세션의 채팅 차단**: lease(최대 5분)가 만료될 때까지 차단되고(살아있는 느린 워커와 구분 불가 — 불가피), 만료 즉시 풀린다(2.3 의 `locked_until > now` 판정). 회수기는 그 뒤 재처리를 예약. 크래시는 드물고 해당 세션 하나·5분 한정이라 수용.

이 lease + 회수기 + 펜싱 조합이 "서버 재시작/워커 장애 시 작업 유실·세션 고착·중복 처리" 를 모두 막는다 — 이번 작업의 핵심.

---

## 11. OpenAI 429 처리와 전역 차단(circuit breaker)

### 11.1 작업 vs 차단 — 영속성 판단

| | `summary_job` | 서킷브레이커 |
|---|---|---|
| 잃으면? | **영영 사라짐**(어느 세션이 대기였는지 재구성 불가) | **다음 호출이 즉시 재발견**(OpenAI 가 또 거부) |
| 그래서 | DB 에 **영속** | 영속 **불필요** |

> 확정 원칙(사용자 명시): 작업 유실을 막아야 하는 `summary_job` 은 DB 에 영속화한다. OpenAI quota exhausted breaker 는 외부 상태에 대한 임시 판단이므로 처음부터 DB 로 영속화하지 않는다. 단일 인스턴스에서는 메모리 breaker 로 충분하고, 복수 인스턴스에서 전역 차단이 필요해지면 Redis TTL 또는 DB `provider_control` 로 확장한다.

### 11.2 동작

- `AI_QUOTA_EXHAUSTED`(크레딧/예산 소진) 감지 시 breaker 를 일정 시간 OPEN — 그 동안 워커는 OpenAI 호출을 건너뛴다(작업은 `scheduleRetry` 로 뒤로 밀림).
- 한도 소진은 작업 단위가 아니라 계정 전역 문제 → 작업마다 헛호출하지 않고 한 번 차단으로 막는 최적화.
- 크래시로 상태가 사라져도 self-healing: 재시작 후 첫 호출이 다시 429 → 즉시 OPEN. 대가는 헛호출 1번.

### 11.3 구현

- Port: `domain/aiChat/out/AiCallCircuitBreaker`(`isOpen()`, `openFor(Duration)`).
- 구현: `infrastructure/.../InMemoryAiCallCircuitBreaker`(`AtomicReference<Instant blockedUntil>` 수준 빈).
- 교체 여지가 있어 Port 로 둔다. 다중 인스턴스 시 Redis TTL/DB 어댑터로 교체만.

> breaker 가 없어도 9절의 작업별 백오프가 크루드한 전역 페이싱을 제공한다 — breaker 는 헛호출을 N→1 로 줄이는 최적화일 뿐 안전 부품이 아니다.

---

## 12. 선제적 호출 속도 제어(outbound pacing) + 라이브 채팅 예산 분리

### 12.1 outbound 토큰 버킷

수만 건이 6시에 적재돼도 한꺼번에 호출하지 않는다. 호출 직전 두 예산 통과:
- **RPM 버킷**: 호출 1건당 1개 차감.
- **TPM 버킷**: `예상 입력 토큰 + 예약 출력 토큰` 만큼 차감.
- 둘 다 확보돼야 호출, 아니면 refill 까지 대기.
- 구현: `bucket4j`(이미 의존성) 기반 Port `OpenAiCallRateLimiter`(`acquire(estimatedTokens)`).

### 12.2 토큰 추정

- 예상 입력 토큰 = **요약 프롬프트(세션 전체 대화)** 기준. 주의: 세션 `accumulatedTokens`(ASSISTANT 출력만)와 다르다 — 그건 자격 게이트(≥500)용.
- 초기엔 글자수 근사(영어 ~4자/토큰, 한국어는 더 토큰-조밀)로 충분. 정밀 페이싱 필요 시 `jtokkit` tokenizer(**확장점**).
- 예약 출력 토큰 = 설정값.

### 12.3 라이브 채팅과의 예산 분리 (3중 방어)

OpenAI 계정 한도는 라이브 채팅 + 제목 생성 + 감상문 배치가 공유.
1. **시간대 분리** — 사용자 적은 새벽 6시 배치.
2. **선제적 throttle** — 배치 워커에 전체 한도의 일부(예: 30~50%)만 할당, 헤드룸 남김.
3. **429 backoff** — 그래도 걸리면 9절로 재시도.

정확한 비율은 운영 지표(라이브 429, 배치 큐 적체, 작업 완료 시간, 토큰 사용량)로 조정. 라이브 채팅 outbound 는 지금 따로 조이지 않고 배치만 캡으로 묶는다.

---

## 13. 트랜잭션 구조 — `TransactionTemplate` 제거

- 7.3 의 단계 분리는 각각 `@Transactional` 인 별도 빈을 트랜잭션 없는 오케스트레이터가 순서 호출하는 선언적 방식. 자기호출 프록시 회피.
- 현재 `TransactionTemplate` 두 곳 제거:
  - `SummaryDraftService` — 작업 큐 구조로 재작성되며 사라짐.
  - `AiChatSessionTitleService` — 같은 방식으로 리팩터링(**별도 커밋**).
- (별건) `CLAUDE.md` 에 "`TransactionTemplate` 신규 사용 금지" 한 줄 규약 추가는 **파일 보호 대상이라 사용자 승인 후**.

---

## 14. 설정값(`@ConfigurationProperties`)

| 값 | 위치 |
|---|---|
| 워커 풀 크기 `C`, 디스패처 주기, **lease 5분**, 회수기 주기, 최대 시도 횟수, 백오프 파라미터 | `domain/summary/config/SummaryJobProperties` |
| RPM/TPM 한도(배치 몫), 예약 출력 토큰 | `infrastructure/ai/openai/...Properties` |
| breaker OPEN 지속 시간 | breaker properties |

비즈니스 룰 상수는 `application.yml` 직접값, 시크릿/환경별 값은 환경변수·프로파일 yml.

---

## 15. 영향 범위 (주요 파일)

신규:
- `model/summary/entity/SummaryJob.java`(컬럼: lock_owner/locked_until 포함), `model/summary/repository/SummaryJobRepository.java`
- `domain/summary/service/EnqueueSummaryJobService.java`, `SummaryGenerationWorker.java`, `SummaryJobTxService.java`
- `infrastructure/.../scheduler/SummaryJobDispatcher.java`, `SummaryJobReaper.java`
- `domain/aiChat/out/AiCallCircuitBreaker.java` + `infrastructure/.../InMemoryAiCallCircuitBreaker.java`
- `domain/.../out/OpenAiCallRateLimiter.java` + bucket4j 어댑터
- `domain/summary/config/SummaryJobProperties.java`

수정:
- `model/aiChat/entity/AiChatSession.java`(LOCKED 의미·`unlock()` 삭제), `model/summary/entity/Summary.java`(session unique), `model/summary/repository/SummaryRepository.java`(단건)
- `model/aiChat/repository/AiChatSessionRepository.java`(목록 CASE: PROCESSING 작업 EXISTS)
- `domain/aiChat/service/SummaryDraftService.java`(적재로), `SummaryScheduler.java`(적재기로)
- `domain/aiChat/service/AiChatMessagePersistService.java`, `domain/summary/service/SummarySearchService.java`(가드: LOCKED 또는 유효 PROCESSING 작업)
- `domain/aiChat/service/policy/SummaryDraftPolicy.java`, `domain/aiChat/dto/SummaryDraftEligibility.java`
- `domain/aiChat/exception/AiChatErrorCode.java`(SESSION_ALREADY_SUMMARIZED)
- `domain/aiChat/service/AiChatSessionTitleService.java`(TransactionTemplate 제거)
- `presentation/controller/aiChat/AiChatController.java`(Swagger)
- 관련 테스트·픽스처 전반

---

## 16. 테스트 계획

서비스 단위 테스트 + 조회 DAO 통합 테스트. 핵심 시나리오:

- 적재기: `ACTIVE` + 토큰 ≥ 500 + 최근 24h 채팅만 PENDING 작업 생성; `LOCKED`/토큰부족/24h 무대화 제외
- 같은 세션 중복 적재 → `active_session_id` unique 로 한 건만
- 선점: 두 워커가 같은 작업 동시 선점 안 함(SKIP LOCKED) — DAO 통합
- 생성 성공: Summary 1행 + 세션 `LOCKED` + 작업 `SUCCEEDED`(active NULL)
- 생성 실패(일시): 세션 `ACTIVE` 유지 + 작업 `PENDING`(attempt_count++, next_attempt_at 미래)
- 생성 실패(상한 초과): 작업 `FAILED`(active NULL), 세션 `ACTIVE`
- **펜싱**: lease 만료 후 재선점된 작업을 옛 claim token 으로 기록 시도 → 0행, 결과 폐기(세션·Summary 불변)
- 차단: 유효 PROCESSING 작업/`LOCKED` 세션은 전송 차단(각각 다른 코드), 적재만 된 `ACTIVE`·고아(lease 만료) PROCESSING 은 전송 가능
- 회수기: `locked_until < now` 인 PROCESSING → `PENDING` + lock_owner/locked_until 해제(세션 불변)
- quota 감지 → breaker OPEN → 워커 호출 건너뜀; 해제 후 재개
- 잠긴 후 재생성 차단: `LOCKED` 세션 적재 요청 → 409 `SESSION_ALREADY_SUMMARIZED`
- `GET .../summary`: 유효 PROCESSING → 409 / `LOCKED`(Summary 존재) → 200 / 없음 → 404
- 목록 표시 상태 3종(ACTIVE/SUMMARIZING/SUMMARIZED) 산출 — DAO 통합

---

## 17. 확장점 (지금 구현 안 함)

- **스케줄러 중복 실행 방지**(복수 인스턴스): ShedLock 등. 지금은 `active_session_id` unique 가 중복 적재를 막아 무해.
- **breaker 전역 공유**: in-memory → Redis TTL 또는 DB `provider_control`.
- **heartbeat**: 회수 시간을 5분 미만으로 줄여야 하면 점유 갱신 도입(지금은 5분 lease 로 충분).
- **세션 last_message 비정규화**: 마지막 메시지 시각/ID 를 매번 `AiChatMessage` 집계로 도출 중 → 세션 컬럼으로.
- **tokenizer 정밀 토큰 계산**: 글자수 근사 → `jtokkit`.
- **워커 동시성 상향**: 한도 커지면 풀 크기 `C` 상향(필요 시 가상 스레드).

---

## 18. 알려진 한계 / 범위 밖

- **캘린더 재설계(Spec 2)**: 세션 중심·마지막 채팅 일시 기준, `bookTitle`/`sessionTitle`/`sessionId`(+nullable `summaryId`) 반환, 감상문은 기존 `findBySessionId` 재사용 — 별도 spec.
- 복수 인스턴스 운영(17절).
- `MIN_ACCUMULATED_TOKENS = 500` 임계값 확정(코드 TODO) — 별도.

---

## 19. 데이터 마이그레이션 (dev RDS)

- dev RDS 는 초기화 후 재생성 가능(2026-06-13 사용자 확인). 마이그레이션 SQL 없이 배포 시 RDS 초기화.
- 테스트 DB 는 H2 인메모리(create-drop) — 엔티티 어노테이션 변경만으로 스키마 반영.
- 신규 `summary_job` 테이블, `summary.ai_chat_session_id` unique, 세션 LOCKED 의미 변경 모두 초기화로 반영.

---

## 20. 프론트 조율 사항

- **#69 기능 철회**: 감상문 완성 후 대화가 다시 **불가능**(입력창 비활성/잠김 표시). #69 로 "완료 후 입력 가능" 으로 바꿨던 흐름을 되돌려야 함.
- 메시지 전송 차단 코드 구분: `SESSION_ALREADY_SUMMARIZED`(잠김) vs `SESSION_LOCKED`(생성 중).
- 생성은 비동기 — 적재 → 폴링(`GET .../summary`: 409 생성 중 → 200 완료 / 404 미생성).
- 목록 표시 상태 값(ACTIVE/SUMMARIZING/SUMMARIZED) 동일하나 SUMMARIZED 는 "잠김(종료)" 의미.
