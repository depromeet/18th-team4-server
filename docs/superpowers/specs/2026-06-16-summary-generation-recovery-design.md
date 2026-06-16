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

1. **비즈니스 룰 전환(#69 역전):** "감상문 생성이 완료된 세션에서 대화를 이어갈 수 있다"(#69, 이미 dev 에 PR #75 로 병합됨)를 **폐기**한다. 새 룰은 **"감상문이 완성되면 그 세션은 종료되어 더 대화할 수 없다"** 이다. 그 결과 세션과 감상문은 **1:1** 이 되고 **재요약(재생성) 경로가 사라진다.**
2. **생성 작업 복구 계층 도입:** 현재 자동 생성(`SummaryScheduler`)은 새벽 6시에 대상 세션을 찾아 그 자리에서 직접 OpenAI 를 호출한다(`CompletableFuture` 팬아웃). 이 구조는 (a) 서버가 죽으면 진행 중이던 작업이 사라지고, (b) 생성 도중 죽으면 세션이 잠긴 채 영구히 멈추며, (c) 한꺼번에 호출이 몰려 OpenAI 호출 한도(rate limit)에 걸리고, (d) 실패한 건만 골라 재시도하기 어렵다. 이를 **DB 작업 큐(`summary_job`) + 워커 + 회수기(reaper)** 로 바꿔 작업이 유실·중복·고착되지 않게 한다.

두 변경을 한 문서로 묶는 이유: **복구 워커가 "생성 성공 시 세션을 종료(CLOSED)시키는" 주체**라, 종료 모델과 작업 큐는 같은 코드 흐름에서 결정된다.

이 문서는 "대규모 트래픽(하루 수만 건)을 가정한 단일 인스턴스" 를 기준으로 설계한다. 복수 인스턴스 대비는 곳곳에 **확장점**으로만 표시하고 지금 구현하지 않는다(17절).

---

## 2. 새 비즈니스 모델 — 세션 종료 모델

### 2.1 세션 생애

```
ACTIVE        대화 가능. (작업이 큐에 PENDING 으로 적재만 된 상태도 여기 — 아직 대화 가능)
  │  워커가 실제 생성 시작 (OpenAI 호출 직전 트랜잭션)
SUMMARIZING   대화 차단(일시적). 생성 중.
  │
  ├─ 성공 → CLOSED   대화 영구 차단(종료). Summary 1행 존재. 끝.
  └─ 실패 → ACTIVE   대화 다시 가능. 다음 회차에 재시도.
```

- **세션 1개 → 감상문 최대 1개(1:1).** 종료 후 재생성이 없으므로 세션당 Summary 는 최대 한 행이다.
- **차단 시점은 "생성 시작" 부터다.** 작업이 큐에 적재만 됐을 때(PENDING)는 아직 `ACTIVE` 라 사용자는 대화할 수 있다. 워커가 실제로 생성을 시작(`SUMMARIZING` 전이)하는 순간부터 차단한다.
- **수정한 감상문이 날아갈 일이 없다.** 재요약이 없으므로 사용자가 편집한 감상문을 덮어쓸 경로가 없다.

### 2.2 세션 상태 enum

`AiChatSession.Status` = **`{ACTIVE, SUMMARIZING, CLOSED}`** (현재 dev 는 `{ACTIVE, LOCKED}`).

| 상태 | 의미 | 대화 |
|---|---|---|
| `ACTIVE` | 일반. 작업 미적재 또는 적재만 됨 | 가능 |
| `SUMMARIZING` | 워커가 생성 중(일시적) | 차단 |
| `CLOSED` | 생성 성공·종료(감상문 존재) | 영구 차단 |

엔티티 메서드(의도 기준, 최종 이름은 구현에서 확정):
- `startSummarizing()` : `ACTIVE → SUMMARIZING` (워커가 생성 시작 시)
- `closeAfterSummary()` : `SUMMARIZING → CLOSED` (성공 시)
- `revertToActive()` : `SUMMARIZING → ACTIVE` (실패 / 회수기 복구 시)
- `isActive()` / `isSummarizing()` / `isClosed()`

종료 상태명을 `CLOSED` 로 두는 것은 메모(#64 계열은 종료를 `CLOSED` 로 표기)와 맞춘다. 현재 dev 의 `LOCKED`(일시 잠금) 는 의미가 다르므로 그대로 쓰지 않고 `SUMMARIZING`(일시) + `CLOSED`(종료) 로 가른다.

### 2.3 Summary 테이블

- 구조는 현재(`createCompleted` write-once)를 **거의 그대로 유지**하되, 비즈니스상 세션당 1행이 된다.
- **`ai_chat_session_id` 에 unique 제약을 (다시) 건다** — 1:1 을 스키마로 못박는다. (#69 reconcile 에서 제거했던 것을 되돌린다. 재생성이 없으므로 안전하고, 동시 적재는 작업 큐의 `active_session_id` unique 가 이미 막는다.)
- 조회는 `findByAiChatSessionId(sessionId)` 단건으로 단순화 가능 (현재 `findFirst...OrderByCreatedAtDesc` 는 1:N 잔재).
- `Summary.edit()`(사용자 편집, 제자리 수정)는 그대로 유지.

---

## 3. #69 역전 — 바뀌는 기존 코드

| 위치 | 현재 (dev, #69) | 변경 |
|---|---|---|
| `AiChatSession.Status` | `{ACTIVE, LOCKED}` | `{ACTIVE, SUMMARIZING, CLOSED}` |
| `AiChatSession` 메서드 | `lock()`/`unlock()`/`isLocked()` | `startSummarizing()`/`closeAfterSummary()`/`revertToActive()`/`isActive()`/`isClosed()` |
| `AiChatMessagePersistService` (전송 가드) | `if (session.isLocked())` 차단 | `if (!session.isActive())` 차단. `SUMMARIZING` → `SESSION_LOCKED`(생성 중), `CLOSED` → 신규 `SESSION_ALREADY_SUMMARIZED`(종료) 로 분기 |
| `SummarySearchService.findBySessionId` | `isLocked()` → 409 | `isSummarizing()` → 409 `SUMMARY_IN_PROGRESS`; `isClosed()` 면 정상적으로 단건 Summary 반환 |
| 목록 JPQL CASE (`AiChatSessionRepository`) | `LOCKED → 'SUMMARIZING'`, `EXISTS Summary → 'SUMMARIZED'` | `SUMMARIZING → 'SUMMARIZING'`, `CLOSED → 'SUMMARIZED'`, else `'ACTIVE'` |
| `AiChatSessionDisplayStatus` | `{ACTIVE, SUMMARIZING, SUMMARIZED}` | 변동 없음(값 동일, 산출 근거만 바뀜) |
| `SummaryDraftPolicy.evaluate` | `LOCKED→IN_PROGRESS`, `ACTIVE→토큰체크` | `SUMMARIZING→IN_PROGRESS`, `CLOSED→ALREADY_SUMMARIZED`, `ACTIVE→토큰체크` |
| `SummaryDraftService` | `execute`(TX1 lock + `@Async` 직접 호출), `executeForScheduler` | **작업 큐로 전환**(4~10절). 수동 경로는 "작업 적재" 로, 스케줄러 경로는 적재기로. `executeForScheduler` 삭제 |
| `SummaryScheduler` | 6시에 대상 찾아 직접 생성(팬아웃) | 6시에 **작업 적재만** (6절) |
| `Summary` | `ai_chat_session_id` unique 없음(1:N) | unique 추가(1:1) |

`SummaryDraftPolicy.IneligibleReason` 에 `ALREADY_SUMMARIZED` 추가, `AiChatErrorCode` 에 `SESSION_ALREADY_SUMMARIZED`("이미 감상문이 생성되어 종료된 세션입니다.") 추가. 429 관련 코드(`AI_RATE_LIMIT_BURST`/`AI_QUOTA_EXHAUSTED`/`AI_PROVIDER_TRANSIENT`)와 `SUMMARY_IN_PROGRESS`/`SESSION_LOCKED` 는 이미 존재 — 재사용.

> #69 의 self-invocation 결함(`execute` 가 같은 클래스 `generateAsync` 를 직접 호출해 `@Async` 프록시를 안 타던 문제)은 **생성이 워커로 빠지면서 자연히 사라진다.**

---

## 4. `summary_job` 테이블 — DB 작업 큐

작업의 의도("이 세션은 감상문을 만들어야 한다")를 DB 에 영속화한다. **작업은 잃으면 복구 불가하므로 반드시 영속한다** (서킷브레이커와 대비 — 11절).

위치: `model/summary/entity/SummaryJob.java`.

```
summary_job
  id                  PK
  ai_chat_session_id  대상 세션 (Long 값만; JPA 연관관계 안 둠)
  active_session_id   미완료(PENDING/RUNNING) = ai_chat_session_id, 완료(SUCCEEDED/FAILED) = NULL
  status              PENDING | RUNNING | SUCCEEDED | FAILED
  attempt_count       시도 횟수 (재시도 백오프 계산 + 상한 판정용)
  next_attempt_at     이 시각 이후 처리 가능 (백오프를 여기 인코딩)
  locked_until        RUNNING 점유 만료 시각 (lease). 지났는데 RUNNING 이면 고아로 보고 회수
  last_error_code     마지막 실패 분류 (관측용)
  last_error_message  마지막 실패 사유 (관측용)
  created_at, updated_at

  UNIQUE (active_session_id)            -- "세션당 활성 작업 1개" (5절)
  INDEX  (status, next_attempt_at)      -- 처리 대상 선점 쿼리용
```

`Status` 는 엔티티 내부 enum. 정적 팩토리 `createPending(sessionId)` 만 노출(도메인 불변식 강제). 상태 전이 메서드:
- `markRunning(lockedUntil)` : `PENDING → RUNNING`, lease 설정
- `markSucceeded()` : `RUNNING → SUCCEEDED`, `active_session_id = NULL`
- `scheduleRetry(nextAttemptAt, code, message)` : `RUNNING → PENDING`, `attempt_count++`, 백오프. `active_session_id` 유지(재시도 동안에도 활성)
- `markFailed(code, message)` : `RUNNING → FAILED`(상한 초과/회복 불가), `active_session_id = NULL`
- `releaseAfterOrphan(nextAttemptAt)` : 회수기용. `RUNNING → PENDING`, lease 해제 (10절)

> GPT 초안의 `RETRY_WAITING` 상태는 두지 않는다 — 백오프는 PENDING 행의 `next_attempt_at` 에 인코딩하면 충분하다. `job_type`(INITIAL/REFRESH), `processing_from/to_message_id`, `chat_summary_state` 는 재요약·증분이 없어졌으므로 전부 불필요. 토큰 추정값은 저장하지 않고 호출 직전 일시 계산(13절).

---

## 5. 중복 방지 — 두 개의 서로 다른 장치

| 막는 문제 | 장치 |
|---|---|
| 같은 세션에 **활성 작업이 여러 개** 생기는 것 (예: 스케줄러 + 수동 경로 동시 적재) | `active_session_id` **unique 제약** |
| 하나의 작업 행을 **여러 워커 스레드가 동시에** 집는 것 | 선점 쿼리의 `FOR UPDATE SKIP LOCKED` |

- `active_session_id` = 미완료 동안만 `ai_chat_session_id`, 완료/실패 시 `NULL`. MySQL 의 unique 인덱스는 NULL 을 여러 개 허용하므로, **완료된 과거 작업은 여러 개 남고 활성 작업만 세션당 하나**로 제한된다. (단, 종료 모델상 세션당 활성 작업은 실질적으로 생애 1회.)
- 적재 시 이미 활성 작업이 있으면 unique 위반으로 insert 가 실패한다 → 새 작업을 만들지 않고 **조용히 건너뛴다**(이미 처리 예약됨). 적재기는 이 위반을 정상 흐름으로 처리한다(예외 잡고 skip, 또는 사전 존재 확인).

---

## 6. 적재기 — `SummaryScheduler` (새벽 6시)

스케줄러는 **OpenAI 를 직접 호출하지 않는다.** 대상 세션을 찾아 `summary_job` PENDING 행만 만든다.

- 트리거: `@Scheduled(cron = "0 0 6 * * *")` (현행 유지).
- 대상 선정: 현행 `findAutoSummaryTargetSessionIds(ACTIVE, MIN_ACCUMULATED_TOKENS, COMPLETED, since=now-24h)` 재사용.
  - 조건 = **세션 `ACTIVE`** + 누적 토큰 ≥ 500 + 마지막 COMPLETED 메시지가 24시간 이내.
  - 종료 모델 덕분에 **이미 요약된 세션은 `CLOSED` 라 `ACTIVE` 필터가 자동 제외**한다 — 별도 "감상문 없음" 조건이 필요 없다.
- 각 대상에 대해 `EnqueueSummaryJobService.execute(sessionId)` 로 PENDING 작업 적재(멱등 — 5절의 unique 로 중복 무해).
- 대량(수만 건) 적재는 청크 단위 insert 로. 적재는 insert 만이라 빠르고, 실제 부하는 워커가 시간에 걸쳐 분산 처리.

> `MIN_ACCUMULATED_TOKENS = 500` 의 임시성(코드 TODO)은 이 작업 범위 밖이다. 그대로 둔다.

---

## 7. 워커 — 선점 → 생성 → 기록

### 7.1 디스패처 (선점·제출)

`SummaryJobDispatcher` — `@Scheduled(fixedDelay = N초)`:
1. 비어 있는 워커 용량만큼 처리 대상 작업을 **선점**한다(아래 7.2 선점 쿼리).
2. 각 작업을 고정 크기 스레드 풀(`Executor`, 크기 `C`)에 제출한다.
3. 풀 크기 `C` 는 **OpenAI 호출 한도에서 역산한 낮은 값** — 동시성 자체를 한도에 맞춰 묶는다(가상 스레드·세마포어 불필요). 실제 호출 속도는 13절의 outbound pacing 이 다시 한 번 조인다.

평소(6시 외)에는 처리 대상이 없어 선점 쿼리가 인덱스만 훑고 즉시 빈손으로 끝난다(저비용).

### 7.2 선점 쿼리 (`SUMMARY_JOB` 단독)

```
status = PENDING AND next_attempt_at <= now
ORDER BY next_attempt_at, id
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

선점한 행은 같은 트랜잭션에서 `markRunning(now + lease)` 로 `RUNNING` 전이 + `locked_until` 설정.

- 구현: Spring Data JPA `@Lock(PESSIMISTIC_WRITE)` + `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))` (Hibernate 의 SKIP LOCKED). **Spring Boot 4 / Hibernate 조합에서 동작 확인 필요** — 안 되면 `@Query(nativeQuery = true)` 로 `... FOR UPDATE SKIP LOCKED` 직접 작성(구현 계획에서 확정).
- repository 는 자기 엔티티(`SummaryJob`)만 반환 — 컨벤션 유지.

### 7.3 한 작업의 처리 흐름 (트랜잭션 분리)

오케스트레이터(`SummaryGenerationWorker`, **트랜잭션 없음**)가 **각각 `@Transactional` 인 별도 빈**(`SummaryJobTxService`)의 메서드를 순서대로 호출한다. OpenAI 호출은 트랜잭션 밖. `TransactionTemplate` 을 쓰지 않는다(13절·메모 규약).

```
[TX-begin]  세션을 비관적 락으로 조회
              ├─ 세션이 ACTIVE 아님(이미 CLOSED 등) → 작업 정리(markSucceeded/스킵)하고 종료
              └─ ACTIVE → startSummarizing() + 대화 이력 로드 → context 반환
[밖]        breaker 열림 확인 → outbound 한도 획득 → aiSummaryClient.generate(messages)
[TX-success] Summary 저장 + session.closeAfterSummary() + job.markSucceeded()  (한 트랜잭션)
[TX-fail]    session.revertToActive() + 실패 분류에 따라 job.scheduleRetry(...) | job.markFailed(...)
```

- 생성에 넣는 대화 = **세션 전체 대화**(현행 `findValidMessagesBySessionIdOrderByCreatedAtAsc`). 증분 아님.
- `TX-begin` 과 `TX-success/fail` 을 **다른 빈**으로 분리해 자기호출 프록시 문제를 피한다.

### 7.4 수동(데모) 경로

현행 `POST .../summary-draft` (데모용)는 그 자리에서 생성하지 않고 **작업을 적재**한다(스케줄러와 같은 `EnqueueSummaryJobService`). 즉시 반환(202). 폴링 계약:

- `SUMMARIZING` → `GET .../summary` 가 409 `SUMMARY_IN_PROGRESS`
- 성공·`CLOSED` → 200 + 감상문
- 실패 → 세션 `ACTIVE` 복귀, 감상문 없음 → 404 `SUMMARY_NOT_FOUND` (사용자가 재요청하면 다시 적재)

적재 자격 검증(`SummaryDraftPolicy`): `ACTIVE` + 토큰 ≥ 500 이어야 적재. `SUMMARIZING`/활성 작업 존재 → 409. `CLOSED` → 409 `SESSION_ALREADY_SUMMARIZED`.

---

## 8. 차단(생성 중) 모델 정리

- 차단은 **세션 상태**로 표현한다(별도 시각 컬럼 없음).
- `SUMMARIZING` 진입은 `TX-begin` 에서 일어난다 = **OpenAI 호출 직전**. 그래서 "작업 적재만 된 상태(PENDING)" 에서는 차단되지 않는다(룰 C).
- 메시지 전송 가드(`AiChatMessagePersistService`)는 `세션이 ACTIVE 가 아니면` 차단. `SUMMARIZING`(일시)·`CLOSED`(영구) 를 메시지로 구분.
- **고착 위험은 회수기가 해소한다**(10절): 생성 중 서버가 죽어 세션이 `SUMMARIZING` + 작업이 `RUNNING` 으로 남아도, lease 만료 후 회수기가 세션을 `ACTIVE` 로 되돌린다. 최악의 차단 지속 = lease 시간(예: 10분).

---

## 9. 실패 처리와 재시도(지수 백오프)

생성 실패 시 작업을 지우지 않고 상태로 남긴다. **실패를 분류**해 다르게 처리한다.

| 분류 | 출처 | 처리 |
|---|---|---|
| 일시적 호출 한도(429 burst) | `AI_RATE_LIMIT_BURST` | `scheduleRetry` — `Retry-After` 있으면 그 시각, 없으면 지수 백오프 |
| 일시적 서버 오류(5xx) | `AI_PROVIDER_TRANSIENT` | `scheduleRetry` — 지수 백오프 |
| 한도 소진(quota) | `AI_QUOTA_EXHAUSTED` | breaker 열기(11절) + `scheduleRetry`(긴 백오프) |
| 회복 불가(기타 4xx 등) | `AI_PROVIDER_ERROR` | `markFailed` (재시도 안 함) |

- 지수 백오프: `attempt_count` 기반 (1분 → 2분 → 4분 → 8분 …), 상한 캡.
- **시도 상한**(`attempt_count >= maxAttempts`) 초과 → `markFailed`(`active_session_id = NULL`). 그러면 그 세션은 다음 날 6시 정기 실행에서 (여전히 `ACTIVE` 이고 조건 충족 시) **새 작업으로 다시 잡힌다.** 실패가 같은 세션의 요약을 영구히 막지 않는다.
- 실패해도 세션은 `revertToActive()` 로 돌아가 사용자는 계속 대화할 수 있다.

429 의 의미·분류(`OpenAiResponseErrorHandler` 가 HTTP 429 를 우리 `TooManyRequestsException` + 분류로 변환)는 **이미 구현돼 있다** — 워커가 그대로 활용한다.

---

## 10. 멈춘 작업 회수기 (reaper)

`SummaryJobReaper` — `@Scheduled(fixedDelay)`:

- 대상: `status = RUNNING AND locked_until < now` (워커가 죽었거나 멈춰 lease 가 만료된 고아 작업).
- 처리(한 트랜잭션): 작업 `releaseAfterOrphan(next_attempt_at = now)` → `PENDING` 복귀(즉시 재선점 가능) **+ 그 세션이 `SUMMARIZING` 이면 `revertToActive()`**.
- 회수기는 **고아 복구만** 담당한다. 일반 재시도(9절)는 워커의 실패 경로가 이미 `PENDING + next_attempt_at` 로 처리하므로 디스패처가 때 되면 다시 집는다.

이 한 장치가 "서버 재시작/워커 장애 시 작업 유실·세션 고착" 을 모두 막는다 — 이번 작업의 핵심 목표.

---

## 11. OpenAI 429 처리와 전역 차단(circuit breaker)

### 11.1 작업 vs 차단 — 영속성 판단의 근거

| | `summary_job` | 서킷브레이커 |
|---|---|---|
| 잃으면? | **영영 사라짐**(어느 세션이 대기였는지 재구성 불가) | **다음 호출이 즉시 재발견**(OpenAI 가 또 거부) |
| 그래서 | DB 에 **영속** | 영속 **불필요** |

> 확정 원칙(사용자 명시): 작업 유실을 막아야 하는 `summary_job` 은 DB 에 영속화한다. OpenAI quota exhausted breaker 는 외부 상태에 대한 임시 판단이므로 처음부터 DB 로 영속화하지 않는다. 단일 인스턴스에서는 메모리 breaker 로 충분하고, 복수 인스턴스에서 전역 차단이 필요해지면 Redis TTL 또는 DB `provider_control` 로 확장한다.

### 11.2 breaker 동작

- `AI_QUOTA_EXHAUSTED`(크레딧/예산 소진) 감지 시 breaker 를 일정 시간 OPEN — 그 동안 워커는 OpenAI 호출을 건너뛴다(작업은 `scheduleRetry` 로 뒤로 밀림).
- 한도 소진은 **작업 단위가 아니라 계정 전역 문제**라, 작업마다 헛호출하지 않고 한 번 차단으로 막는 최적화.
- 크래시로 breaker 상태가 사라져도 self-healing: 재시작 후 첫 호출이 다시 429 를 받아 즉시 OPEN. 대가는 "헛호출 1번".

### 11.3 구현

- Port: `domain/aiChat/out/AiCallCircuitBreaker` (`isOpen()`, `openFor(Duration)` 정도).
- 구현: `infrastructure/.../InMemoryAiCallCircuitBreaker` — 단순한 `volatile`/`AtomicReference<Instant blockedUntil>` 빈.
- 교체 여지가 있어 Port 로 둔다(CLAUDE.md "교체 여지 있으면 Port" 기준). 다중 인스턴스로 가면 Redis TTL/DB 어댑터로 교체만.

> breaker 가 아예 없어도 9절의 작업별 백오프가 크루드한 전역 페이싱을 제공한다 — breaker 는 "N개 작업이 각자 1번씩 헛호출 → 1번으로 줄이는" 최적화일 뿐, 안전을 책임지는 부품이 아니다.

---

## 12. 선제적 호출 속도 제어(outbound pacing) + 라이브 채팅 예산 분리

### 12.1 outbound 토큰 버킷

수만 건이 6시에 적재돼도 한꺼번에 호출하지 않는다. 워커는 OpenAI 호출 직전 두 예산을 통과해야 한다.

- **분당 요청 수(RPM) 버킷**: 호출 1건당 1개 차감.
- **분당 토큰 수(TPM) 버킷**: `예상 입력 토큰 + 예약 출력 토큰` 만큼 차감.
- 둘 다 확보돼야 호출, 아니면 refill 까지 대기.
- 구현: `bucket4j`(이미 의존성) 기반 Port `OpenAiCallRateLimiter`(`acquire(estimatedTokens)`).

### 12.2 토큰 추정

- 예상 입력 토큰 = **요약 프롬프트(세션 전체 대화)** 기준. 주의: 세션의 `accumulatedTokens`(ASSISTANT 출력만 누적)와 **다르다** — 그건 자격 게이트(≥500)용이고, 호출 토큰 추정은 실제 프롬프트 전체 길이로 한다.
- 초기엔 글자수 근사(영어 ~4자/토큰, 한국어는 더 토큰-조밀)로 충분. 정밀 페이싱이 필요하면 `jtokkit` 같은 tokenizer 도입(**확장점**).
- 예약 출력 토큰 = 설정값(`reservedOutputTokens`).

### 12.3 라이브 채팅과의 예산 분리

OpenAI 계정 한도는 라이브 채팅 + 제목 생성 + 감상문 배치가 공유한다. 배치가 다 먹어 라이브 채팅이 429 를 맞지 않도록:

- **3중 방어**: (1) 시간대 분리 — 사용자가 적은 새벽 6시 배치 / (2) 선제적 throttle — 배치 워커에 전체 한도의 일부(예: 30~50%)만 할당 / (3) 429 backoff — 그래도 걸리면 9절로 재시도.
- 정확한 비율은 고정값이 아니라 운영 지표(라이브 채팅 429, 배치 큐 적체, 작업 완료 시간, 토큰 사용량)를 보며 조정. 처음엔 보수적으로.
- 라이브 채팅 outbound 는 지금 별도로 재량 줄이지 않는다(현행 유지) — 배치만 캡으로 묶어 헤드룸을 남긴다.

---

## 13. 트랜잭션 구조 — `TransactionTemplate` 제거

- 7.3 의 `TX-begin / (밖) / TX-success|fail` 은 **각각 `@Transactional` 인 별도 빈 메서드**를 **트랜잭션 없는 오케스트레이터**가 순서대로 호출하는 선언적 방식으로 구현한다. 자기호출 프록시 문제를 피하려 단계를 다른 빈으로 분리.
- 현재 `TransactionTemplate` 을 쓰는 **두 곳을 제거**한다:
  - `SummaryDraftService` — 이번 작업으로 작업 큐 구조로 재작성되며 자연히 사라짐.
  - `AiChatSessionTitleService` — 같은 방식(선언적 분리)으로 리팩터링(**별도 커밋**).
- (별건) `CLAUDE.md` 에 "`TransactionTemplate` 신규 사용 금지" 한 줄 규약 추가는 **파일이 보호 대상이라 사용자 승인 후** 진행.

---

## 14. 설정값(`@ConfigurationProperties`)

| 값 | 위치 | 비고 |
|---|---|---|
| 워커 풀 크기 `C`, 디스패처 주기, lease 시간, 최대 시도 횟수, 백오프 파라미터 | `domain/summary/config/SummaryJobProperties` | 도메인 비즈니스 룰 |
| RPM/TPM 한도(배치 몫), 예약 출력 토큰 | `infrastructure/ai/openai/...Properties` | 외부 API 설정 |
| breaker OPEN 지속 시간 | breaker 쪽 properties | 외부 상태 대응 |

비즈니스 룰 상수는 `application.yml` 직접값(재배포 가능), 시크릿/환경별 값은 환경변수·프로파일 yml(메모 규약).

---

## 15. 영향 범위 (주요 파일)

신규:
- `model/summary/entity/SummaryJob.java`, `model/summary/repository/SummaryJobRepository.java`
- `domain/summary/service/EnqueueSummaryJobService.java`(적재), `SummaryGenerationWorker.java`(오케스트레이터), `SummaryJobTxService.java`(TX 빈들)
- `infrastructure/.../scheduler/SummaryJobDispatcher.java`, `SummaryJobReaper.java`
- `domain/aiChat/out/AiCallCircuitBreaker.java` + `infrastructure/.../InMemoryAiCallCircuitBreaker.java`
- `domain/.../out/OpenAiCallRateLimiter.java` + bucket4j 어댑터
- `domain/summary/config/SummaryJobProperties.java`

수정(역전·전환):
- `model/aiChat/entity/AiChatSession.java`(상태 enum + 메서드), `model/summary/entity/Summary.java`(session unique), `model/summary/repository/SummaryRepository.java`(단건 조회)
- `model/aiChat/repository/AiChatSessionRepository.java`(목록 CASE)
- `domain/aiChat/service/SummaryDraftService.java`(작업 적재로), `SummaryScheduler.java`(적재기로)
- `domain/aiChat/service/AiChatMessagePersistService.java`, `domain/summary/service/SummarySearchService.java`(가드)
- `domain/aiChat/service/policy/SummaryDraftPolicy.java`, `domain/aiChat/dto/SummaryDraftEligibility.java`(IneligibleReason)
- `domain/aiChat/exception/AiChatErrorCode.java`(SESSION_ALREADY_SUMMARIZED 추가)
- `domain/aiChat/service/AiChatSessionTitleService.java`(TransactionTemplate 제거)
- `presentation/controller/aiChat/AiChatController.java`(Swagger 설명: 종료 모델 반영)
- 관련 테스트·픽스처 전반(LOCKED→SUMMARIZING/CLOSED 전제 수정)

---

## 16. 테스트 계획

서비스 단위 테스트 + 조회 DAO 통합 테스트(컨벤션). 핵심 시나리오:

- 적재기: `ACTIVE` + 토큰 ≥ 500 + 최근 24h 채팅 세션만 PENDING 작업 생성, `CLOSED`/토큰부족/24h 무대화는 제외
- 같은 세션 중복 적재 시 `active_session_id` unique 로 한 건만 남음
- 선점: 두 워커가 같은 작업을 동시에 집지 않음(SKIP LOCKED) — DAO 통합
- 생성 성공: Summary 1행 + 세션 `CLOSED` + 작업 `SUCCEEDED`(active_session_id NULL)
- 생성 실패(일시): 세션 `ACTIVE` 복귀 + 작업 `PENDING`(attempt_count++, next_attempt_at 미래)
- 생성 실패(상한 초과): 작업 `FAILED`(active_session_id NULL), 세션 `ACTIVE`
- 차단: `SUMMARIZING`/`CLOSED` 세션은 메시지 전송 차단(각각 다른 코드), 적재만 된 `ACTIVE` 는 전송 가능
- 회수기: lease 만료된 `RUNNING` 작업 → `PENDING` + 세션 `SUMMARIZING → ACTIVE`
- quota 감지 시 breaker OPEN → 워커가 호출 건너뜀; OPEN 해제 후 재개
- 종료 후 재생성 차단: `CLOSED` 세션에 적재 요청 → 409 `SESSION_ALREADY_SUMMARIZED`
- `GET .../summary`: `SUMMARIZING` → 409 / `CLOSED`(Summary 존재) → 200 / 없음 → 404
- 목록 표시 상태 3종(ACTIVE/SUMMARIZING/SUMMARIZED) 산출 — DAO 통합

---

## 17. 확장점 (지금 구현 안 함, 복수 인스턴스 시)

- **스케줄러 중복 실행 방지**: 복수 인스턴스에서 6시 적재기가 동시에 도는 것 → ShedLock 등(지금은 `active_session_id` unique 가 중복 적재를 막아 무해).
- **breaker 전역 공유**: in-memory → Redis TTL 또는 DB `provider_control`.
- **세션 last_message 비정규화**: 마지막 메시지 시각/ID 를 매번 `AiChatMessage` 집계로 도출 중. 대규모에서 세션 컬럼으로 비정규화하면 조회 비용↓(지금은 코어 엔티티 불변 유지).
- **tokenizer 정밀 토큰 계산**: 글자수 근사 → `jtokkit`.
- **워커 동시성 상향**: 한도가 커지면 풀 크기 `C` 상향(필요 시 가상 스레드 검토).

---

## 18. 알려진 한계 / 범위 밖

- **캘린더 재설계(Spec 2)**: 세션 중심·마지막 채팅 일시 기준 조회, `bookTitle`/`sessionTitle`/`sessionId`(+nullable `summaryId`) 반환, 감상문은 기존 `findBySessionId` 재사용 — 별도 spec.
- 복수 인스턴스 운영(17절).
- `MIN_ACCUMULATED_TOKENS = 500` 임계값 확정(코드 TODO) — 별도.

---

## 19. 데이터 마이그레이션 (dev RDS)

- dev RDS 는 초기화 후 재생성 가능(2026-06-13 사용자 확인). 마이그레이션 SQL 없이 **배포 시 RDS 초기화**.
- 테스트 DB 는 H2 인메모리(create-drop) — 엔티티 어노테이션 변경만으로 스키마 반영.
- 신규 `summary_job` 테이블, `summary.ai_chat_session_id` unique, 세션 status 값 변경 모두 초기화로 반영.

---

## 20. 프론트 조율 사항

- **#69 기능 철회**: 감상문 완료 후 대화가 다시 **불가능**해진다(입력창 비활성/종료 표시). #69 로 "완료 후 입력 가능" 으로 바꿨던 화면 흐름을 되돌려야 함.
- 종료 세션 메시지 전송 시 새 에러 코드 `SESSION_ALREADY_SUMMARIZED`(종료) vs `SESSION_LOCKED`(생성 중) 구분.
- 감상문 생성은 비동기 — 적재(202) → 폴링(`GET .../summary`: 409 생성 중 → 200 완료 / 404 미생성) 계약.
- 목록 표시 상태 값(ACTIVE/SUMMARIZING/SUMMARIZED)은 동일하나 SUMMARIZED 는 이제 "종료" 의미.
