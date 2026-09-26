# 0005 — 컨텍스트 요약 워커의 동시성·트랜잭션 설계

- **상태**: 채택
- **날짜**: 2026-07-07
- **관련**: [0001](0001-aichat-title-generation-scheduler-bulkhead.md)(작업 큐 골격 원형), [0003](0003-aichat-concurrency-moderation-http-client.md)(외부 호출을 트랜잭션 밖으로), 이슈 #92(요약 큐 적재 락 타임아웃 사고)
- **관련 코드**:
  - `domain/aiChat/service/ContextSummaryJobLifecycleService` — 준비/기록/회수/실패의 트랜잭션 단계
  - `domain/aiChat/service/ContextSummaryWorker` — 비-트랜잭션 오케스트레이터(준비 → LLM → 기록)
  - `domain/aiChat/service/EnqueueContextSummaryJobService` + `ContextSummaryJobInserter` — 멱등 적재
  - `model/aiChat/entity/AiChatContextSummary` — 세션당 1행, `summarized_up_to_message_id`, `version`
  - `model/aiChat/entity/AiChatContextSummaryJob` — `active_session_id`, `lock_owner`, `locked_until`

## 한 줄 요약

요약은 **비동기 작업 큐 + 워커**로 돈다. 워커 한 바퀴는 외부 LLM 호출을 트랜잭션 밖에 두려고 **준비(A)·LLM·기록(B)** 세 단계로 쪼개진다. 이 구조가 만드는 동시성·트랜잭션 문제들(느린 LLM 호출 중 남의 갱신, 동시 쓰기, 큐 적재 경합, 고아 작업, 중복 적재)을 **다층 방어**로 막는다: 체크포인트 원자적 전진 + 낙관적 검사(version) + 비관적 락 + SKIP LOCKED/READ COMMITTED + `lock_owner` 펜싱 + `active_session_id` unique. 각 방어가 서로 다른 창을 덮는다.

## 전제 — 체크포인트 모델

요약이 "어디까지 덮었는지"는 세션당 1행인 `ai_chat_context_summary.summarized_up_to_message_id`에 저장한다. 값은 **메시지 id**다. `AiChatMessage.id`가 IDENTITY라 시간순으로 단조 증가하므로, `id > 체크포인트` = 아직 요약 안 된 원문(delta), `id ≤ 체크포인트` = 이미 요약이 덮음으로 **경계가 중복도 구멍도 없이** 갈린다. 요약이 한 번도 없으면 행이 없고 체크포인트는 0으로 본다. 읽기는 여러 곳(조립·트리거·준비)에서 하지만 **전진은 워커의 기록 단계 한 곳에서만** 한다.

## 배경 — 이 구조가 만드는 문제

외부 LLM 호출은 트랜잭션 안에서 하면 안 된다(DB 커넥션을 수 초 붙잡아 풀 고갈 — [0003], CLAUDE.md 규칙9). 그래서 워커는:

```
준비(A) @Transactional  →  LLM 호출(트랜잭션 밖, 수 초)  →  기록(B) @Transactional
```

이 분할이 곧 문제의 근원이다. A와 B 사이에 락을 놓게 되므로, 그 창에서 (1) 다른 워커가 같은 세션 요약을 먼저 갱신하거나, (2) 이 작업이 고아가 되어 재선점될 수 있다. 여기에 큐 자체의 동시 적재·선점 경합(#92)이 겹친다.

## 결정

### 1. 워커 한 바퀴를 준비·LLM·기록 3단계로 분할, 외부 호출은 트랜잭션 밖

`ContextSummaryWorker`가 비-트랜잭션 오케스트레이터로 `prepareGeneration`(A) → `aiContextSummaryClient.generate`(밖) → `recordSuccess`(B)를 순서대로 부른다. `TransactionTemplate`을 쓰지 않고 선언적 `@Transactional` 협력자 빈으로 트랜잭션을 나눈다. 준비 단계는 계산 입력을 `ContextSummaryGenerationContext`(sessionId·이전 요약·이전 version·요약할 원문·전진할 체크포인트)로 **스냅샷**해 넘긴다.

### 2. 기록은 요약문·체크포인트·version을 한 커밋으로 원자적 전진

`AiChatContextSummary.applyUpdate`가 **요약문 + `summarized_up_to_message_id` + `version += 1`**을 같은 엔티티에 함께 쓰고, `job.markSucceeded()`까지 기록 트랜잭션 B 하나로 커밋한다. 그래서 "체크포인트만 앞서고 요약문은 옛 내용"인 **찢어진 상태가 생길 수 없다.** 체크포인트가 가리키는 지점과 요약문 내용은 항상 일치한다.

### 3. 낙관적 검사(version + 단조 증가)로 LLM 호출 창의 남의 갱신을 폐기

A에서 요약 version을 `previousVersion`으로 스냅샷하고, B에서 락 잡고 읽은 현재 version과 비교한다. 두 조건이 **모두** 참이어야 반영한다:

- `versionMatches`: 준비 때 본 version 그대로 → 그 사이 아무도 요약을 안 건드림.
- `advancesSummarizedUpTo`: 전진할 체크포인트가 현재보다 앞 → 역행·중복 방지.

하나라도 어긋나면 **예외를 던지지 않고 조용히 폐기 + job 성공 종료**한다. 재시도해도 같은 낡은 입력이라 결과가 같으므로 재시도가 무의미하기 때문이다. JPA `@Version` 자동 낙관적 락 대신 수동 비교를 택한 이유가 이 "충돌 시 재시도가 아니라 폐기" 흐름을 직접 제어하기 위해서다.

**안 하면 생기는 문제** — 실제 발생 경로는 lease 만료 → 고아 회수다: W1이 체크포인트 100 기준으로 준비하고 LLM 호출(느림) 중 lease가 만료되어 작업이 회수되고, W2가 재선점해 요약을 체크포인트 200까지 전진시킨 뒤, W1의 LLM이 뒤늦게 응답하는 상황.

- `versionMatches` 없으면 → **잃어버린 갱신**: W1이 옛 기준으로 계산한 요약문이 이미 200까지 덮은 요약을 150까지만 덮는 옛 내용으로 되돌린다.
- `advancesSummarizedUpTo` 없으면 → **체크포인트 역행**: 200→150으로 뒤로 가, 151~200이 다시 delta로 취급되어 원문으로 재노출되고 나중에 또 요약된다(헛일 반복, 요약문 오염).

이 "뒤늦게 온 W1"은 아래 결정 5의 job 펜싱만으로도 막히지만, **펜싱은 job 행(작업 신원)을, 낙관적 검사는 요약 행(여러 작업이 공유하는 실제 데이터)을** 지킨다. 상위 불변식(결정 6의 세션당 활성 작업 1개)이 미래에 깨져도, 요약 행 무결성은 이 검사가 데이터 계층에서 job 장부와 독립적으로 보장한다.

### 4. 기록 시 비관적 락으로 동시 쓰기를 직렬화

`recordSuccess`는 요약 행을 `findBySessionIdForUpdate`(SELECT … FOR UPDATE)로 읽어, 같은 세션 요약을 두 트랜잭션이 동시에 쓰는 것을 직렬화한다. **비관적 락은 "지금 이 순간 동시 쓰기"를, 결정 3의 version은 "LLM 호출 중 낀 남의 갱신"을** 각각 막는다 — 서로 다른 시간 창을 덮는 두 겹이다.

### 5. 작업 선점은 SKIP LOCKED + READ COMMITTED

`claimOne`은 `findClaimable`을 SKIP LOCKED로 조회해 여러 워커가 겹치지 않게 하나씩 집는다. 격리 수준은 **READ COMMITTED** — 기본 REPEATABLE READ의 범위 스캔이 거는 gap lock이 신규 PENDING INSERT와 충돌해 적재가 수십 초 막힌 사고(#92)의 처방이다. 감상문 큐(0001)와 같은 골격.

### 6. `lock_owner` 펜싱 + lease + 고아 회수

작업 행에 `lock_owner`(워커 UUID)와 `locked_until`(lease 만료)을 둔다. 모든 변경 메서드는 `findByIdForUpdate` 후 `isOwnedBy(owner)`로 **소유권을 확인**해, 재선점된 작업을 뒤늦게 돌아온 옛 워커가 건드리지 못하게 펜싱한다. lease가 만료된 고아 작업은 `reclaimOrphans`가 PENDING으로 되돌려 즉시 재선점 가능하게 한다(워커가 죽어도 작업이 영원히 묶이지 않음).

### 7. 세션당 활성 작업 1개(unique) + REQUIRES_NEW 적재로 멱등

`active_session_id`는 PENDING/PROCESSING 동안만 설정되고 unique 제약이 걸려, **한 세션에 활성 요약 작업이 최대 1개**다. 적재(`EnqueueContextSummaryJobService`)는 "체크포인트 이후 원문 토큰 합 > 임계값"일 때만 하고, 실제 INSERT는 `ContextSummaryJobInserter`가 **REQUIRES_NEW**로 격리한다. 그래서 동시 적재로 unique 위반이 나도 호출자 트랜잭션을 오염시키지 않고 조용히 무시(멱등)할 수 있다. 순간 락 대기 실패(#92)만 짧은 backoff로 구제한다.

## 다층 방어 — 어느 가드가 어느 창을 덮나

| 창(위험) | 가드 |
|---|---|
| 같은 순간 동시 쓰기(요약 행) | 비관적 락 `findBySessionIdForUpdate` (결정 4) |
| LLM 호출 중 낀 남의 갱신 | 낙관적 version + 단조 검사 (결정 3) |
| 여러 워커가 같은 작업 선점 | SKIP LOCKED (결정 5) |
| 뒤늦게 온 옛 워커의 작업 변경 | `lock_owner` 펜싱 (결정 6) |
| 워커 사망으로 묶인 작업 | lease + `reclaimOrphans` (결정 6) |
| 동시/중복 적재 | `active_session_id` unique + REQUIRES_NEW (결정 7) |
| 큐 스캔 gap lock ↔ 신규 INSERT 충돌 | READ COMMITTED (결정 5, #92) |
| 요약문·체크포인트 찢어진 상태 | 한 커밋 원자적 갱신 (결정 2) |

## 버린 대안

- **`TransactionTemplate`으로 트랜잭션 수동 제어** — 프로젝트 금지(CLAUDE.md 규칙9, 안티패턴 전파원). 선언적 `@Transactional` 협력자 빈 + 비-트랜잭션 오케스트레이터로 대체.
- **JPA `@Version` 자동 낙관적 락** — 충돌 시 예외를 던져 재시도로 유도한다. 여기선 충돌 = 낡은 입력이라 재시도가 무의미하므로, 수동 비교로 "조용히 폐기 + 작업 성공 종료"를 직접 제어(결정 3).
- **외부 LLM 호출을 트랜잭션 안에서** — 커넥션 풀 고갈([0003]). 호출을 트랜잭션 밖으로 빼는 3단계 분할로 대체.
- **세션당 여러 작업 허용** — 중복 요약·경합만 늘고 이득이 없다. 활성 1개로 못박음(결정 7).
