# 감상문 생성 OpenAI Batch API 전환 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 감상문 자동 생성을 OpenAI Batch API 비동기 경로로 옮기고, 수동(데모) 요청은 동기 단건 경로로 분리한다.

**Architecture:** `summary_job` 에 `execution_mode {SYNC, BATCH}` 를 두어 한 큐를 두 소비자로 가른다. SYNC 는 기존 동기 워커가 단건 처리(보호장치 없이 timeout·실패분류·중복방지만), BATCH 는 다중 builder 가 토큰 예산으로 청크를 묶어 Batch API 에 제출하고 단일 collector 가 결과를 수집한다. 데이터 안전은 `active_session_id` unique + 멱등 저장(이미 LOCKED → 폐기)으로 보장한다.

**Tech Stack:** Spring Boot 4 / Java 25 / JPA(MySQL, SKIP LOCKED) / Spring AI(OpenAI) / JUnit5 + Mockito + AssertJ.

**설계 근거:** [`docs/superpowers/specs/2026-06-17-summary-batch-pivot-design.md`](../specs/2026-06-17-summary-batch-pivot-design.md)

**용어:** "점유 시한" = `locked_until`(이 시각 지나면 회수 대상). "회수기" = reaper. "선점" = SKIP LOCKED claim.

---

## 파일 구조 (생성/수정 맵)

**삭제 (Plan 2 — 새 설계에서 호출처 0):**
- `domain/summary/out/SummaryCallRateLimiter.java`
- `infrastructure/ai/openai/ratelimit/SummaryCallRateLimiterImpl.java`
- `infrastructure/ai/openai/ratelimit/SummaryRateLimitProperties.java`
- `domain/aiChat/out/AiCallCircuitBreaker.java`
- `infrastructure/ai/openai/circuitbreaker/InMemoryAiCallCircuitBreaker.java`
- 테스트: `SummaryCallRateLimiterImplTest`, `InMemoryAiCallCircuitBreakerTest`

**수정:**
- `model/summary/entity/SummaryJob.java` — `ExecutionMode` enum, 상태 2종 추가, `openAiBatchId`, batch 전이 메서드
- `model/summary/repository/SummaryJobRepository.java` — execution_mode 필터 선점, batch 청크 선점, 회수 범위 확장, 차단 판정 쿼리
- `domain/summary/service/SummaryGenerationWorker.java` — limiter/breaker/pacing 제거, SYNC 만 선점
- `domain/summary/service/SummaryJobLifecycleService.java` — claim 에 execution_mode, 회수 범위
- `domain/summary/service/EnqueueSummaryJobService.java` / `SummaryJobInserter.java` — `executionMode` 인자
- `domain/summary/config/SummaryJobProperties.java` — breaker/paced 제거
- `infrastructure/aiChat/scheduler/SummaryScheduler.java` — BATCH 로 적재
- `domain/aiChat/service/SummaryDraftService.java` — SYNC 로 적재
- `domain/aiChat/service/AiChatMessagePersistService.java` / `domain/summary/service/SummarySearchService.java` — 차단 판정 교체
- `model/aiChat/repository/AiChatSessionRepository.java` — SUMMARIZING 도출 확장
- `infrastructure/summary/scheduler/SummaryJobReaper.java` — (변경 없음, 회수 범위는 쿼리에서)
- `src/main/resources/application.yml` — Plan2 키 제거, batch 키 추가
- 테스트 픽스처: `SummaryJobFixture.java`, `AiChatSessionFixture.java`

**생성:**
- `model/summary/entity/OpenAiBatch.java`, `model/summary/repository/OpenAiBatchRepository.java`
- `domain/summary/out/SummaryBatchClient.java` (Port), `infrastructure/ai/openai/batch/SummaryBatchClientImpl.java` (Adapter)
- `domain/summary/dto/SummaryBatchBuildItem.java`, `domain/summary/dto/SummaryBatchRequestItem.java`, `domain/summary/dto/SummaryBatchResultItem.java`
- `domain/summary/service/SummaryBatchSubmitService.java` (builder)
- `domain/summary/service/SummaryBatchCollectService.java` (collector)
- `domain/summary/config/SummaryBatchProperties.java`
- `infrastructure/summary/scheduler/SummaryBatchSubmitScheduler.java`, `SummaryBatchCollectScheduler.java`

---

## Phase A — Plan 2 삭제 & 동기 워커 단순화

목표: 호출 속도 limiter·quota 차단기를 제거하고, 동기 워커를 "선점 → 준비 → 호출 → 기록" 단순 루프로 줄인다. 이 단계 후에도 기존 동기 처리는 동작한다(페이싱만 사라짐).

> **⚠️ 실행 순서 주의 (A·B·C 는 맞물린 한 컴파일 단위):** 아래 A1 워커는 `SummaryJob.ExecutionMode` 와 `claimOne(ExecutionMode, owner)` 를 참조한다 — 이 둘은 B1·C1 에서 생긴다. 따라서 **권장 적용 순서는 B1 → B2 → C1 → A1 → A2** 다. (문서는 주제별로 A·B·C 로 묶었지만, 컴파일은 데이터 모델(B)·선점 필터(C)가 먼저 있어야 워커(A)가 맞는다.) subagent-driven-development 로 실행할 때 이 순서로 태스크를 디스패치할 것.

### Task A1: limiter·breaker 사용처 제거 — 워커 단순화

**Files:**
- Modify: `src/main/java/com/readum/domain/summary/service/SummaryGenerationWorker.java`
- Test: `src/test/java/com/readum/domain/summary/service/SummaryGenerationWorkerTest.java`

- [ ] **Step 1: 워커 테스트를 단순화된 협력자로 재작성 (실패 확인)**

`SummaryGenerationWorkerTest` 에서 `SummaryCallRateLimiter`, `AiCallCircuitBreaker`, `SummaryTokenEstimator` mock 과 관련 케이스(차단 시 미선점, 페이싱 requeue)를 제거한다. 남길/갱신할 케이스:

```java
// 협력자: lifecycleService, aiSummaryClient 만 mock. owner 는 워커 내부 생성 UUID → anyString() 매처.
@Test
void 작업이_없으면_처리하지_않고_false() {
    given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(null);
    assertThat(worker.processOne()).isFalse();
}

@Test
void 정상_생성되면_성공_기록한다() {
    given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(10L);
    SummaryGenerationContext ctx = new SummaryGenerationContext(1L, 2L, List.of()); // 기존 3-인자 record 그대로
    given(lifecycleService.prepareGeneration(eq(10L), anyString())).willReturn(ctx);
    SummaryDraftResult result = new SummaryDraftResult("t", "b");
    given(aiSummaryClient.generate(ctx.messages())).willReturn(result);

    worker.processOne();

    verify(lifecycleService).recordSuccess(eq(10L), anyString(), eq(2L), eq(result));
}

@Test
void quota_소진_429는_재시도로_기록한다() {
    // TooManyRequestsException(AI_QUOTA_EXHAUSTED) → recordFailure(retryable=true)
}

@Test
void 비_429_4xx는_즉시_FAILED로_기록한다() {
    // NonTransientAiException → recordFailure(retryable=false, AI_PROVIDER_ERROR)
}

@Test
void 5xx는_재시도로_기록한다() {
    // TransientAiException → recordFailure(retryable=true, AI_PROVIDER_TRANSIENT)
}
```

> `claimOne(owner)` 시그니처가 `claimOne(ExecutionMode)` 로 바뀌는 것은 Task C1 에서 확정한다. Phase A 만 단독 컴파일하려면 임시로 `claimOne()` 유지 후 C1 에서 인자 추가. **권장: A1 과 C1 을 연속 실행**해 시그니처를 한 번에 맞춘다. (아래 구현은 최종형 = `claimOne(ExecutionMode.SYNC)` 기준으로 작성)

- [ ] **Step 2: 테스트 실행 — 컴파일 실패/케이스 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryGenerationWorkerTest"`
Expected: FAIL (limiter/breaker 심볼 없음 또는 케이스 불일치)

- [ ] **Step 3: 워커 구현 단순화**

`SummaryGenerationWorker` 를 아래로 교체. limiter·breaker·tokenEstimator 의존성과 `requeueForPacing`·permit 분기 제거.

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerationWorker {

    private final SummaryJobLifecycleService lifecycleService;
    private final AiSummaryClient aiSummaryClient;

    /** 처리할 SYNC 작업이 없을 때까지 계속 선점·처리한다. */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** SYNC 작업 하나를 시도. 처리했으면 true, 없으면 false. */
    public boolean processOne() {
        String owner = UUID.randomUUID().toString();
        Long jobId = lifecycleService.claimOne(SummaryJob.ExecutionMode.SYNC, owner);
        if (jobId == null) {
            return false;
        }
        runJob(jobId, owner);
        return true;
    }

    private void runJob(Long jobId, String owner) {
        SummaryGenerationContext context = lifecycleService.prepareGeneration(jobId, owner);
        if (context == null) {
            return;
        }
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(context.messages());
        } catch (TooManyRequestsException e) {
            // 수동 경로는 차단기 없음 — quota/burst 모두 재시도로만 처리(상한 도달 시 FAILED).
            lifecycleService.recordFailure(jobId, owner, true,
                    e.getErrorCode().name(), e.getMessage(), retryAtFrom(e.getRateLimitInfo()));
            return;
        } catch (NonTransientAiException e) {
            log.warn("감상문 생성 회복 불가 오류 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, false,
                    AiChatErrorCode.AI_PROVIDER_ERROR.name(), e.getMessage(), null);
            return;
        } catch (TransientAiException e) {
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        } catch (Exception e) {
            log.warn("감상문 생성 실패 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        }
        lifecycleService.recordSuccess(jobId, owner, context.userBookId(), result);
    }

    private LocalDateTime retryAtFrom(RateLimitInfo info) {
        if (info != null && info.retryAfter() != null) {
            return LocalDateTime.now().plus(info.retryAfter());
        }
        return null;
    }
}
```

> `claimOne(ExecutionMode, owner)` 와 `ExecutionMode` 는 Task B1·C1 에서 생긴다. A 와 B·C 를 연속으로 진행해 컴파일을 맞춘다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryGenerationWorkerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor(summary): 동기 워커에서 호출 속도 limiter·quota 차단기 분리"
```

### Task A2: limiter·breaker·프로퍼티 파일과 테스트 삭제

**Files:**
- Delete: 위 "삭제" 목록의 5개 main + 2개 test
- Modify: `domain/summary/config/SummaryJobProperties.java`, `src/main/resources/application.yml`

- [ ] **Step 1: 잔존 사용처 확인**

Run: `grep -rn "SummaryCallRateLimiter\|AiCallCircuitBreaker\|SummaryRateLimitProperties\|InMemoryAiCallCircuitBreaker" src`
Expected: Task A1 후 워커에는 없음. 남은 건 삭제 대상 파일 자신뿐이어야 한다.

- [ ] **Step 2: 파일 삭제**

```bash
git rm src/main/java/com/readum/domain/summary/out/SummaryCallRateLimiter.java \
       src/main/java/com/readum/infrastructure/ai/openai/ratelimit/SummaryCallRateLimiterImpl.java \
       src/main/java/com/readum/infrastructure/ai/openai/ratelimit/SummaryRateLimitProperties.java \
       src/main/java/com/readum/domain/aiChat/out/AiCallCircuitBreaker.java \
       src/main/java/com/readum/infrastructure/ai/openai/circuitbreaker/InMemoryAiCallCircuitBreaker.java \
       src/test/java/com/readum/infrastructure/ai/openai/ratelimit/SummaryCallRateLimiterImplTest.java \
       src/test/java/com/readum/infrastructure/ai/openai/circuitbreaker/InMemoryAiCallCircuitBreakerTest.java
```

- [ ] **Step 3: `SummaryJobProperties` 에서 차단기·페이싱 키 제거**

`breakerOpenSeconds`, `pacedRetrySeconds` 필드와 `breakerOpen()` 메서드를 제거. `reservedOutputTokens` 는 batch 청킹에서 재사용하므로 **유지**. 결과:

```java
@Validated
@ConfigurationProperties(prefix = "summary-job")
public record SummaryJobProperties(
        @Positive int poolSize,
        @Positive long dispatchIntervalMs,
        @Positive long reaperIntervalMs,
        @Positive long leaseSeconds,
        @Positive int maxAttempts,
        @Positive long baseBackoffSeconds,
        @Positive int reservedOutputTokens
) {
    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    public LocalDateTime nextAttemptFrom(LocalDateTime now, int attemptCount) {
        long seconds = baseBackoffSeconds * (1L << Math.min(attemptCount, 10));
        return now.plusSeconds(seconds);
    }
}
```

- [ ] **Step 4: `application.yml` 정리**

`summary-job` 에서 `breaker-open-seconds`, `paced-retry-seconds` 줄 삭제. `summary.rate-limit:` 블록(라인 90~95) 전체 삭제.

- [ ] **Step 5: 전체 컴파일·테스트**

Run: `./gradlew compileJava compileTestJava`
Expected: SUCCESS (잔존 참조 없음)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "chore(summary): Plan2(limiter·breaker) 및 관련 설정 삭제"
```

---

## Phase B — 데이터 모델: execution_mode + 상태 확장 + openai_batch 연결

### Task B1: `SummaryJob` 에 ExecutionMode·상태·batch 전이 추가

**Files:**
- Modify: `src/main/java/com/readum/model/summary/entity/SummaryJob.java`
- Modify: `src/test/java/com/readum/model/summary/entity/SummaryJobFixture.java`
- Test: `src/test/java/com/readum/model/summary/entity/SummaryJobTransitionTest.java` (생성)

- [ ] **Step 1: 전이 테스트 작성 (실패 확인)**

```java
package com.readum.model.summary.entity;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class SummaryJobTransitionTest {

    @Test
    void BATCH_작업은_BATCH_모드_PENDING으로_생성된다() {
        SummaryJob job = SummaryJob.createPending(7L, SummaryJob.ExecutionMode.BATCH);
        assertThat(job.getExecutionMode()).isEqualTo(SummaryJob.ExecutionMode.BATCH);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getActiveSessionId()).isEqualTo(7L);
    }

    @Test
    void 빌드_점유는_BATCH_BUILDING으로_owner와_점유시한을_설정한다() {
        SummaryJob job = SummaryJob.createPending(7L, SummaryJob.ExecutionMode.BATCH);
        LocalDateTime until = LocalDateTime.now().plusMinutes(5);
        job.startBatchBuilding("owner-1", until);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.BATCH_BUILDING);
        assertThat(job.isOwnedBy("owner-1")).isTrue();
        assertThat(job.getLockedUntil()).isEqualTo(until);
    }

    @Test
    void 제출은_SUBMITTED로_batchId를_설정하고_점유를_해제한다() {
        SummaryJob job = SummaryJob.createPending(7L, SummaryJob.ExecutionMode.BATCH);
        job.startBatchBuilding("owner-1", LocalDateTime.now().plusMinutes(5));
        job.markSubmitted(42L);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUBMITTED);
        assertThat(job.getOpenAiBatchId()).isEqualTo(42L);
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
        assertThat(job.isOwnedBy("owner-1")).isFalse();
    }
}
```

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew test --tests "com.readum.model.summary.entity.SummaryJobTransitionTest"`
Expected: FAIL (ExecutionMode/메서드 없음)

- [ ] **Step 3: 엔티티 수정**

`SummaryJob` 에 다음을 반영:
- 상태 enum: `PENDING, PROCESSING, BATCH_BUILDING, SUBMITTED, SUCCEEDED, FAILED`
- 새 enum: `public enum ExecutionMode { SYNC, BATCH }`
- 새 필드:
  ```java
  @Enumerated(EnumType.STRING)
  @Column(name = "execution_mode", nullable = false, length = 10)
  private ExecutionMode executionMode;

  @Column(name = "open_ai_batch_id")
  private Long openAiBatchId;
  ```
- 전체필드 생성자(@AllArgsConstructor PACKAGE) 인자 순서 확정 (픽스처·createPending 이 위치 인자로 호출):
  `(id, aiChatSessionId, activeSessionId, executionMode, status, lockOwner, lockedUntil, openAiBatchId, attemptCount, nextAttemptAt, lastErrorCode, lastErrorMessage, createdAt, updatedAt)`
- 팩토리/메서드:
  ```java
  public static SummaryJob createPending(Long aiChatSessionId, ExecutionMode executionMode) {
      LocalDateTime now = LocalDateTime.now();
      return new SummaryJob(
              null, aiChatSessionId, aiChatSessionId, executionMode, Status.PENDING,
              null, null, null, 0, now, null, null, now, now
      );
  }

  /** builder 가 청크로 점유 — BATCH_BUILDING. */
  public void startBatchBuilding(String owner, LocalDateTime lockedUntil) {
      this.status = Status.BATCH_BUILDING;
      this.lockOwner = owner;
      this.lockedUntil = lockedUntil;
      this.updatedAt = LocalDateTime.now();
  }

  /** OpenAI 제출 성공 — SUBMITTED. 점유 해제(워커가 들고 있지 않음), batch 연결. */
  public void markSubmitted(Long openAiBatchId) {
      this.status = Status.SUBMITTED;
      this.openAiBatchId = openAiBatchId;
      this.lockOwner = null;
      this.lockedUntil = null;
      this.updatedAt = LocalDateTime.now();
  }
  ```
- `isOwnedBy` 를 BATCH_BUILDING 도 인정하도록 확장:
  ```java
  public boolean isOwnedBy(String owner) {
      return (this.status == Status.PROCESSING || this.status == Status.BATCH_BUILDING)
              && owner != null && owner.equals(this.lockOwner);
  }
  ```
- `scheduleRetry`, `markFailed`, `releaseAfterOrphan` 에 `this.openAiBatchId = null;` 추가(재큐/실패 시 batch 연결 해제). `markSucceeded` 는 openAiBatchId 유지(어느 batch 가 만들었는지 audit). 기존 인덱스에 batch 선점용 추가:
  ```java
  @Index(name = "idx_summary_job_mode_claim", columnList = "execution_mode, status, next_attempt_at")
  ```

- [ ] **Step 4: 픽스처 갱신**

`SummaryJobFixture` 의 두 팩토리를 새 생성자 시그니처로 갱신하고, batch 상태 픽스처 추가:

```java
public static SummaryJob persistedPending(Long id, Long sessionId, LocalDateTime nextAttemptAt) {
    LocalDateTime now = LocalDateTime.now();
    return new SummaryJob(
            id, sessionId, sessionId, SummaryJob.ExecutionMode.SYNC, SummaryJob.Status.PENDING,
            null, null, null, 0, nextAttemptAt, null, null, now, now);
}

public static SummaryJob persistedProcessing(Long id, Long sessionId, String owner, LocalDateTime lockedUntil) {
    LocalDateTime now = LocalDateTime.now();
    return new SummaryJob(
            id, sessionId, sessionId, SummaryJob.ExecutionMode.SYNC, SummaryJob.Status.PROCESSING,
            owner, lockedUntil, null, 0, now, null, null, now, now);
}

/** BATCH 모드 처리 대기. builder 선점 테스트용. */
public static SummaryJob persistedBatchPending(Long id, Long sessionId, LocalDateTime nextAttemptAt) {
    LocalDateTime now = LocalDateTime.now();
    return new SummaryJob(
            id, sessionId, sessionId, SummaryJob.ExecutionMode.BATCH, SummaryJob.Status.PENDING,
            null, null, null, 0, nextAttemptAt, null, null, now, now);
}

/** BATCH_BUILDING 점유 작업. 회수기 테스트용(lockedUntil 로 만료 제어). */
public static SummaryJob persistedBatchBuilding(Long id, Long sessionId, String owner, LocalDateTime lockedUntil) {
    LocalDateTime now = LocalDateTime.now();
    return new SummaryJob(
            id, sessionId, sessionId, SummaryJob.ExecutionMode.BATCH, SummaryJob.Status.BATCH_BUILDING,
            owner, lockedUntil, null, 0, now, null, null, now, now);
}

/** SUBMITTED 작업(점유 시한 없음). collector·미회수 테스트용. openAiBatchId 연결. */
public static SummaryJob persistedSubmitted(Long id, Long sessionId, Long openAiBatchId) {
    LocalDateTime now = LocalDateTime.now();
    return new SummaryJob(
            id, sessionId, sessionId, SummaryJob.ExecutionMode.BATCH, SummaryJob.Status.SUBMITTED,
            null, null, openAiBatchId, 0, now, null, null, now, now);
}
```

- [ ] **Step 5: 테스트 통과 + 컴파일**

Run: `./gradlew test --tests "com.readum.model.summary.entity.SummaryJobTransitionTest" && ./gradlew compileTestJava`
Expected: PASS / SUCCESS

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat(summary): SummaryJob 에 execution_mode·BATCH 상태·batch 전이 추가"
```

### Task B2: 적재 경로에 executionMode 전달

**Files:**
- Modify: `EnqueueSummaryJobService.java`, `SummaryJobInserter.java`, `SummaryScheduler.java`, `SummaryDraftService.java`
- Test: `src/test/java/com/readum/domain/summary/service/EnqueueSummaryJobServiceTest.java`

- [ ] **Step 1: 테스트 갱신 (실패 확인)**

`EnqueueSummaryJobServiceTest` 에서 `execute(sessionId, mode)` 시그니처로 바꾸고, BATCH/SYNC 각각 `insertPending(sessionId, mode)` 위임을 검증:

```java
@Test
void 활성작업_없으면_주어진_모드로_적재한다() {
    given(summaryJobRepository.existsByActiveSessionId(7L)).willReturn(false);
    service.execute(7L, SummaryJob.ExecutionMode.BATCH);
    verify(summaryJobInserter).insertPending(7L, SummaryJob.ExecutionMode.BATCH);
}

@Test
void 활성작업_있으면_적재하지_않는다() {
    given(summaryJobRepository.existsByActiveSessionId(7L)).willReturn(true);
    service.execute(7L, SummaryJob.ExecutionMode.SYNC);
    verify(summaryJobInserter, never()).insertPending(anyLong(), any());
}
```

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.EnqueueSummaryJobServiceTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
// EnqueueSummaryJobService
public void execute(Long sessionId, SummaryJob.ExecutionMode executionMode) {
    if (summaryJobRepository.existsByActiveSessionId(sessionId)) {
        return;
    }
    try {
        summaryJobInserter.insertPending(sessionId, executionMode);
    } catch (DataIntegrityViolationException e) {
        log.debug("감상문 작업 적재 경합 — 이미 활성 작업 존재 sessionId={}", sessionId);
    }
}

// SummaryJobInserter
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void insertPending(Long sessionId, SummaryJob.ExecutionMode executionMode) {
    summaryJobRepository.save(SummaryJob.createPending(sessionId, executionMode));
}
```

호출부 갱신:
- `SummaryScheduler.enqueueDailySummaryJobs()`: `enqueueSummaryJobService.execute(sessionId, SummaryJob.ExecutionMode.BATCH);`
- `SummaryDraftService.execute()`: `enqueueSummaryJobService.execute(sessionId, SummaryJob.ExecutionMode.SYNC);`

- [ ] **Step 4: 테스트 통과**

Run: `./gradlew test --tests "com.readum.domain.summary.service.EnqueueSummaryJobServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(summary): 적재 경로에 execution_mode(자동=BATCH, 수동=SYNC) 전달"
```

---

## Phase C — SYNC 경로 분리 (선점 필터 + 회수 범위)

### Task C1: execution_mode 별 선점 + 회수 범위 확장

**Files:**
- Modify: `SummaryJobRepository.java`, `SummaryJobLifecycleService.java`
- Test: `src/test/java/com/readum/domain/summary/service/SummaryJobLifecycleServiceTest.java`, `...ReapTest.java`

- [ ] **Step 1: 테스트 작성 (실패 확인)**

`claimOne(mode, owner)` 가 해당 모드의 PENDING 만 선점하고, 회수가 PROCESSING·BATCH_BUILDING 모두를 대상으로 함을 검증:

```java
@Test
void SYNC_선점은_SYNC_PENDING만_가져온다() {
    SummaryJob sync = SummaryJobFixture.persistedPending(1L, 100L, LocalDateTime.now());
    given(summaryJobRepository.findClaimable(
            eq(SummaryJob.ExecutionMode.SYNC), eq(SummaryJob.Status.PENDING), any(), any()))
        .willReturn(List.of(sync));
    Long claimed = service.claimOne(SummaryJob.ExecutionMode.SYNC, "owner-1");
    assertThat(claimed).isEqualTo(1L);
    assertThat(sync.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING);
}
```

ReapTest: `findOrphaned` 가 PROCESSING·BATCH_BUILDING 둘 다 반환하도록 mock, `releaseAfterOrphan` 호출 검증(기존 패턴 유지).

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryJobLifecycleServiceTest"`
Expected: FAIL

- [ ] **Step 3: 리포지토리·서비스 구현**

`SummaryJobRepository.findClaimable` 에 mode 필터 추가:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("""
        select summaryJob
          from SummaryJob summaryJob
         where summaryJob.executionMode = :executionMode
           and summaryJob.status = :status
           and summaryJob.nextAttemptAt <= :now
         order by summaryJob.nextAttemptAt asc
                , summaryJob.id asc
        """)
List<SummaryJob> findClaimable(
        @Param("executionMode") SummaryJob.ExecutionMode executionMode,
        @Param("status") SummaryJob.Status status,
        @Param("now") LocalDateTime now,
        Pageable pageable);
```

`findOrphaned` 의 status 조건을 둘로 확장:

```java
@Query("""
        select summaryJob
          from SummaryJob summaryJob
         where summaryJob.status in (
                   com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                 , com.readum.model.summary.entity.SummaryJob.Status.BATCH_BUILDING
               )
           and summaryJob.lockedUntil < :now
         order by summaryJob.lockedUntil asc
        """)
List<SummaryJob> findOrphaned(@Param("now") LocalDateTime now, Pageable pageable);
```

`SummaryJobLifecycleService.claimOne`:

```java
@Transactional
public Long claimOne(SummaryJob.ExecutionMode executionMode, String owner) {
    List<SummaryJob> candidates = summaryJobRepository.findClaimable(
            executionMode, SummaryJob.Status.PENDING, LocalDateTime.now(),
            PageRequest.of(0, 1));
    if (candidates.isEmpty()) {
        return null;
    }
    SummaryJob job = candidates.get(0);
    job.claim(owner, LocalDateTime.now().plus(properties.lease()));
    return job.getId();
}
```

> `claim()` 은 PROCESSING 으로 가는 기존 메서드 — SYNC 단건 처리에 그대로 사용. (BATCH 빌드 점유는 `startBatchBuilding` 으로 Phase F 에서 사용)

- [ ] **Step 4: 테스트 통과**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryJobLifecycleService*"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(summary): execution_mode 별 선점 + BATCH_BUILDING 회수 범위 확장"
```

---

## Phase D — 채팅 차단 확장 (BATCH_BUILDING/SUBMITTED 포함)

### Task D1: "생성 중" 판정 쿼리 교체 + 호출부·세션목록 도출 확장

**Files:**
- Modify: `SummaryJobRepository.java` (`existsActiveProcessingJob` → 차단 판정으로 본문 교체)
- Modify: `AiChatSessionRepository.java` (SUMMARIZING CASE)
- Test: `src/test/java/com/readum/model/summary/repository/SummaryJobRepositoryBlockingTest.java` (생성, DAO 통합 테스트)

- [ ] **Step 1: DAO 통합 테스트 작성 (실패 확인)**

차단 판정: PROCESSING(유효 점유)/BATCH_BUILDING(유효 점유)/SUBMITTED → true, PENDING → false, 점유 만료 PROCESSING/BATCH_BUILDING → false.

```java
@DataJpaTest // RDS 연동 컨벤션을 따르는 기존 통합 테스트 설정에 맞춤(프로젝트 표준 따를 것)
class SummaryJobRepositoryBlockingTest {

    @Autowired SummaryJobRepository repository;

    @Test
    void SUBMITTED_작업은_차단으로_본다() {
        repository.save(SummaryJobFixture.persistedSubmitted(null, 100L, 1L));
        assertThat(repository.existsBlockingSummaryJob(100L, LocalDateTime.now())).isTrue();
    }

    @Test
    void 유효점유_BATCH_BUILDING은_차단으로_본다() {
        repository.save(SummaryJobFixture.persistedBatchBuilding(null, 100L, "o", LocalDateTime.now().plusMinutes(5)));
        assertThat(repository.existsBlockingSummaryJob(100L, LocalDateTime.now())).isTrue();
    }

    @Test
    void 점유만료_BATCH_BUILDING은_차단_아니다() {
        repository.save(SummaryJobFixture.persistedBatchBuilding(null, 100L, "o", LocalDateTime.now().minusMinutes(1)));
        assertThat(repository.existsBlockingSummaryJob(100L, LocalDateTime.now())).isFalse();
    }

    @Test
    void PENDING은_차단_아니다() {
        repository.save(SummaryJobFixture.persistedBatchPending(null, 100L, LocalDateTime.now()));
        assertThat(repository.existsBlockingSummaryJob(100L, LocalDateTime.now())).isFalse();
    }
}
```

> 통합 테스트의 DB 설정은 프로젝트의 기존 Repository 통합 테스트(`EnqueueSummaryJobServiceIntegrationTest` 등)와 동일한 방식을 따른다 (RDS 접속 컨벤션 — TestContainer MySQL 금지).

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew test --tests "com.readum.model.summary.repository.SummaryJobRepositoryBlockingTest"`
Expected: FAIL (`existsBlockingSummaryJob` 없음)

- [ ] **Step 3: 리포지토리 구현 — 판정 쿼리 교체**

`existsActiveProcessingJob` 를 `existsBlockingSummaryJob` 로 교체(이름이 의미를 드러냄):

```java
/** "지금 생성 중(차단)" 판정 — 유효 점유 PROCESSING/BATCH_BUILDING 또는 SUBMITTED 작업이 그 세션에 있는가. */
@Query("""
        select case when count(summaryJob) > 0 then true else false end
          from SummaryJob summaryJob
         where summaryJob.aiChatSessionId = :sessionId
           and (
                 (summaryJob.status in (
                        com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                      , com.readum.model.summary.entity.SummaryJob.Status.BATCH_BUILDING
                  ) and summaryJob.lockedUntil > :now)
              or summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.SUBMITTED
           )
        """)
boolean existsBlockingSummaryJob(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);
```

- [ ] **Step 4: 호출부 2곳 교체**

- `AiChatMessagePersistService.loadHistory`: `summaryJobRepository.existsBlockingSummaryJob(sessionId, LocalDateTime.now())`
- `SummarySearchService` (line 103): 동일 교체.

- [ ] **Step 5: 세션 목록 SUMMARIZING 도출 확장**

`AiChatSessionRepository.findSessionsByUserBookIdAndOwnerInternal` 의 `when exists(...) then 'SUMMARIZING'` 서브쿼리를 차단 판정과 동일 조건으로 교체:

```sql
when exists (
         select 1
           from SummaryJob summaryJob
          where summaryJob.aiChatSessionId = aiChatSession.id
            and (
                  (summaryJob.status in (
                         com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                       , com.readum.model.summary.entity.SummaryJob.Status.BATCH_BUILDING
                   ) and summaryJob.lockedUntil > :now)
               or summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.SUBMITTED
            )
     ) then 'SUMMARIZING'
```

- [ ] **Step 6: 테스트 통과 + 전체 컴파일**

Run: `./gradlew test --tests "com.readum.model.summary.repository.SummaryJobRepositoryBlockingTest" && ./gradlew compileJava compileTestJava`
Expected: PASS / SUCCESS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "feat(aiChat): 채팅 차단 판정을 BATCH_BUILDING·SUBMITTED 까지 확장"
```

---

## Phase E — openai_batch 엔티티

### Task E1: `OpenAiBatch` 엔티티 + 리포지토리

**Files:**
- Create: `model/summary/entity/OpenAiBatch.java`, `model/summary/repository/OpenAiBatchRepository.java`
- Create: `src/test/java/com/readum/model/summary/entity/OpenAiBatchFixture.java`
- Test: `src/test/java/com/readum/model/summary/entity/OpenAiBatchTransitionTest.java`

- [ ] **Step 1: 전이 테스트 작성 (실패 확인)**

```java
class OpenAiBatchTransitionTest {
    @Test
    void 제출_상태로_생성된다() {
        OpenAiBatch batch = OpenAiBatch.createSubmitted("batch_AAA", "file_in", 3);
        assertThat(batch.getStatus()).isEqualTo(OpenAiBatch.Status.SUBMITTED);
        assertThat(batch.getBatchId()).isEqualTo("batch_AAA");
        assertThat(batch.getJobCount()).isEqualTo(3);
    }

    @Test
    void 완료_기록은_결과파일을_설정한다() {
        OpenAiBatch batch = OpenAiBatch.createSubmitted("batch_AAA", "file_in", 3);
        batch.markCompleted("file_out", "file_err");
        assertThat(batch.getStatus()).isEqualTo(OpenAiBatch.Status.COMPLETED);
        assertThat(batch.getOutputFileId()).isEqualTo("file_out");
        assertThat(batch.getErrorFileId()).isEqualTo("file_err");
    }

    @Test
    void 실패_기록은_FAILED로_바꾼다() {
        OpenAiBatch batch = OpenAiBatch.createSubmitted("batch_AAA", "file_in", 3);
        batch.markFailed();
        assertThat(batch.getStatus()).isEqualTo(OpenAiBatch.Status.FAILED);
    }
}
```

- [ ] **Step 2: 실행 — 실패 확인** / Run: `./gradlew test --tests "...OpenAiBatchTransitionTest"` / Expected: FAIL

- [ ] **Step 3: 엔티티 구현**

```java
@Getter
@Entity
@Table(name = "openai_batch", indexes = {
        @Index(name = "idx_openai_batch_status", columnList = "status")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class OpenAiBatch {

    public enum Status { SUBMITTED, COMPLETED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 100)
    private String batchId;            // OpenAI 발급 식별자

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "input_file_id", length = 100)
    private String inputFileId;

    @Column(name = "output_file_id", length = 100)
    private String outputFileId;

    @Column(name = "error_file_id", length = 100)
    private String errorFileId;

    @Column(name = "job_count", nullable = false)
    private int jobCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** OpenAI 제출 성공 직후(현재 PR: 제출 후 기록). */
    public static OpenAiBatch createSubmitted(String batchId, String inputFileId, int jobCount) {
        LocalDateTime now = LocalDateTime.now();
        return new OpenAiBatch(null, batchId, Status.SUBMITTED, inputFileId, null, null, jobCount, now, now);
    }

    public void markCompleted(String outputFileId, String errorFileId) {
        this.status = Status.COMPLETED;
        this.outputFileId = outputFileId;
        this.errorFileId = errorFileId;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = Status.FAILED;
        this.updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 4: 리포지토리 + 픽스처**

```java
public interface OpenAiBatchRepository extends JpaRepository<OpenAiBatch, Long> {
    List<OpenAiBatch> findByStatus(OpenAiBatch.Status status);
}
```

`OpenAiBatchFixture` (전체필드 생성자 호출, `@TestOnly`): `submitted(id, batchId, jobCount)`.

- [ ] **Step 5: 테스트 통과** / Run: `./gradlew test --tests "...OpenAiBatchTransitionTest"` / Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat(summary): openai_batch 엔티티·리포지토리 추가"
```

---

## Phase F — Batch builder (청크 선점 → JSONL → 제출 → 기록)

### Task F1: Batch API Port + DTO

**Files:**
- Create: `domain/summary/out/SummaryBatchClient.java`
- Create: `domain/summary/dto/SummaryBatchBuildItem.java`, `domain/summary/dto/SummaryBatchRequestItem.java`, `domain/summary/dto/SummaryBatchResultItem.java`

- [ ] **Step 1: Port·DTO 정의 (구현 없음 — 다음 태스크들이 사용할 계약)**

```java
// domain/summary/dto/SummaryBatchBuildItem.java
// builder 가 청크 선점 단계에서 만든다. SYNC 의 SummaryGenerationContext(3-인자)를 건드리지 않기 위해 전용 record.
public record SummaryBatchBuildItem(Long jobId, Long sessionId, Long userBookId, List<AiChatMessage> messages) {}

// domain/summary/dto/SummaryBatchRequestItem.java
public record SummaryBatchRequestItem(String customId, List<AiChatMessage> messages) {}

// domain/summary/dto/SummaryBatchResultItem.java
/** error == null 이면 성공. 성공 시 title/body, 실패 시 error 분류. */
public record SummaryBatchResultItem(
        String customId, SummaryDraftResult result,
        boolean failed, boolean retryable, String errorCode, String errorMessage) {

    public static SummaryBatchResultItem success(String customId, SummaryDraftResult result) {
        return new SummaryBatchResultItem(customId, result, false, false, null, null);
    }
    public static SummaryBatchResultItem failure(String customId, boolean retryable, String errorCode, String errorMessage) {
        return new SummaryBatchResultItem(customId, null, true, retryable, errorCode, errorMessage);
    }
}

// domain/summary/out/SummaryBatchClient.java
public interface SummaryBatchClient {
    /** 입력 파일 업로드 + batch 생성. batchId·inputFileId 반환. */
    BatchSubmission submit(List<SummaryBatchRequestItem> items);

    /** batch 상태 조회. */
    BatchStatus pollStatus(String batchId);

    /** 완료된 batch 의 결과를 custom_id 단위로 파싱해 반환. */
    List<SummaryBatchResultItem> fetchResults(BatchStatus status);

    record BatchSubmission(String batchId, String inputFileId) {}
    record BatchStatus(String batchId, State state, String outputFileId, String errorFileId) {
        public enum State { RUNNING, COMPLETED, FAILED }
    }
}
```

- [ ] **Step 2: 컴파일 확인** / Run: `./gradlew compileJava` / Expected: SUCCESS

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "feat(summary): Batch API Port(SummaryBatchClient)·DTO 정의"
```

### Task F2: 청킹 로직 (builder 핵심)

**Files:**
- Create: `domain/summary/config/SummaryBatchProperties.java`
- Create: `domain/summary/service/SummaryBatchSubmitService.java`
- Modify: `SummaryJobLifecycleService.java` (batch 청크 선점·제출 기록 트랜잭션 단계 추가)
- Modify: `SummaryJobRepository.java` (batch 청크 선점 쿼리)
- Test: `src/test/java/com/readum/domain/summary/service/SummaryBatchSubmitServiceTest.java`

- [ ] **Step 1: 청킹 테스트 작성 (실패 확인)**

토큰 상한 / maxJobsPerBatch 중 먼저 닿는 데서 끊는다:

```java
@Test
void 토큰_상한에_닿으면_거기서_청크를_끊는다() {
    // properties: chunkTokenLimit=3000, maxJobsPerBatch=100, reservedOutputTokens=1000
    // 각 작업 추정 토큰이 2000(=메시지+예약) 이면, 2개째에서 4000>3000 → 첫 청크는 1개
    // tokenEstimator·lifecycle·client mock 으로 검증
    ...
    assertThat(submittedItems).hasSize(1);
}

@Test
void maxJobsPerBatch에_닿으면_건수로_끊는다() {
    // chunkTokenLimit 매우 큼, maxJobsPerBatch=2, PENDING 5건 → 첫 제출은 2건
    assertThat(submittedItems).hasSize(2);
}

@Test
void 제출_성공하면_openai_batch_기록하고_작업들을_SUBMITTED로_만든다() {
    // client.submit() → BatchSubmission("batch_AAA","file_in")
    // verify: openAiBatchRepository.save(status SUBMITTED, jobCount=N), 각 job.markSubmitted(batchId)
}
```

- [ ] **Step 2: 실행 — 실패 확인** / Run: `./gradlew test --tests "...SummaryBatchSubmitServiceTest"` / Expected: FAIL

- [ ] **Step 3: Properties 구현**

```java
@Validated
@ConfigurationProperties(prefix = "summary-batch")
public record SummaryBatchProperties(
        @Positive int builderConcurrency,     // 동시 builder 수 (초기 2)
        @Positive long chunkTokenLimit,       // 청크당 토큰 상한
        @Positive int maxJobsPerBatch,        // 청크당 작업 수 안전 상한
        @Positive long submitIntervalMs,      // builder 디스패치 주기
        @Positive long collectIntervalMs,     // collector 폴링 주기
        @Positive int buildLeaseSeconds       // BATCH_BUILDING 점유 시한(업로드+제출 최악 소요 초과)
) {
    public Duration buildLease() { return Duration.ofSeconds(buildLeaseSeconds); }
}
```

- [ ] **Step 4: 청크 선점 쿼리 + lifecycle 단계**

`SummaryJobRepository` 에 batch 청크 선점(SKIP LOCKED, 여러 건):

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("""
        select summaryJob
          from SummaryJob summaryJob
         where summaryJob.executionMode = com.readum.model.summary.entity.SummaryJob.ExecutionMode.BATCH
           and summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PENDING
           and summaryJob.nextAttemptAt <= :now
         order by summaryJob.nextAttemptAt asc
                , summaryJob.id asc
        """)
List<SummaryJob> findClaimableBatch(@Param("now") LocalDateTime now, Pageable pageable);
```

`SummaryJobLifecycleService` 에 트랜잭션 단계 2개:

```java
/** BATCH PENDING 을 maxJobsPerBatch 만큼 후보로 선점(BATCH_BUILDING), 빌드 아이템 리스트 반환. */
@Transactional
public List<SummaryBatchBuildItem> claimBatchChunk(String owner, int maxJobs, Duration buildLease) {
    LocalDateTime now = LocalDateTime.now();
    List<SummaryJob> candidates = summaryJobRepository.findClaimableBatch(now, PageRequest.of(0, maxJobs));
    List<SummaryBatchBuildItem> items = new ArrayList<>();
    for (SummaryJob job : candidates) {
        AiChatSession session = aiChatSessionRepository.findByIdForUpdate(job.getAiChatSessionId()).orElse(null);
        if (session == null || session.getStatus() != AiChatSession.Status.ACTIVE) {
            job.markSucceeded(); // 종료/부재 세션은 무해 종료(스냅샷 대상 아님)
            continue;
        }
        job.startBatchBuilding(owner, now.plus(buildLease));
        List<AiChatMessage> messages =
                aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(session.getId());
        items.add(new SummaryBatchBuildItem(job.getId(), job.getAiChatSessionId(), session.getUserBookId(), messages));
    }
    return items;
}

/** 제출 성공 기록 — openai_batch 저장 + 해당 작업들 SUBMITTED. */
@Transactional
public void recordSubmission(List<Long> jobIds, String owner, String batchId, String inputFileId) {
    OpenAiBatch batch = openAiBatchRepository.save(
            OpenAiBatch.createSubmitted(batchId, inputFileId, jobIds.size()));
    for (Long jobId : jobIds) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job != null && job.isOwnedBy(owner)) {
            job.markSubmitted(batch.getId());
        }
    }
}

/** 제출 실패 시 빌드 점유를 PENDING 으로 되돌린다(시도 횟수 미증가 — 재청킹 대상). */
@Transactional
public void releaseBuilding(List<Long> jobIds, String owner) {
    LocalDateTime now = LocalDateTime.now();
    for (Long jobId : jobIds) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job != null && job.isOwnedBy(owner)) {
            job.releaseAfterOrphan(now);
        }
    }
}
```

> `SummaryGenerationContext` 에 `sessionId`(=aiChatSessionId) 가 필요하면 record 에 필드 추가. 기존 `(sessionId, userBookId, messages)` 의 `sessionId` 가 실제로는 session.getId() 라 동일 — 별도 필드 불필요하면 4번째 인자는 생략하고 기존 3-인자 유지. (`prepareGeneration` 과 시그니처 통일)

`openAiBatchRepository` 의존성을 `SummaryJobLifecycleService` 에 주입.

- [ ] **Step 5: builder 서비스 구현 (오케스트레이터, 비-TX)**

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryBatchSubmitService {

    private final SummaryJobLifecycleService lifecycleService;
    private final SummaryTokenEstimator tokenEstimator;
    private final SummaryBatchClient batchClient;
    private final SummaryJobProperties jobProperties;
    private final SummaryBatchProperties batchProperties;

    /** 한 청크를 선점·제출한다. 처리할 게 있었으면 true. */
    public boolean submitOneChunk() {
        String owner = UUID.randomUUID().toString();
        List<SummaryBatchBuildItem> claimed = lifecycleService.claimBatchChunk(
                owner, batchProperties.maxJobsPerBatch(), batchProperties.buildLease());
        if (claimed.isEmpty()) {
            return false;
        }
        // 토큰 예산으로 실제 제출 묶음을 자른다(나머지는 BATCH_BUILDING 인 채 점유 시한 만료 후 회수→재청킹).
        List<SummaryBatchBuildItem> chunk = new ArrayList<>();
        List<SummaryBatchRequestItem> items = new ArrayList<>();
        long tokenSum = 0;
        for (SummaryBatchBuildItem item : claimed) {
            int est = tokenEstimator.estimate(item.messages(), jobProperties.reservedOutputTokens());
            if (!chunk.isEmpty() && tokenSum + est > batchProperties.chunkTokenLimit()) {
                break;
            }
            tokenSum += est;
            chunk.add(item);
            items.add(new SummaryBatchRequestItem("summaryjob-" + item.jobId(), item.messages()));
        }
        List<Long> overflow = claimed.subList(chunk.size(), claimed.size())
                .stream().map(SummaryBatchBuildItem::jobId).toList();
        if (!overflow.isEmpty()) {
            lifecycleService.releaseBuilding(overflow, owner); // 이번 청크에 못 들어간 점유분 즉시 반환
        }
        List<Long> jobIds = chunk.stream().map(SummaryBatchBuildItem::jobId).toList();
        try {
            SummaryBatchClient.BatchSubmission sub = batchClient.submit(items);
            lifecycleService.recordSubmission(jobIds, owner, sub.batchId(), sub.inputFileId());
            log.info("감상문 batch 제출 완료 jobs={} batchId={}", jobIds.size(), sub.batchId());
        } catch (Exception e) {
            log.error("감상문 batch 제출 실패 — 점유 반환 jobs={}", jobIds.size(), e);
            lifecycleService.releaseBuilding(jobIds, owner);
        }
        return true;
    }
}
```

> builder 는 전용 record `SummaryBatchBuildItem`(jobId 포함, Task F1)을 쓴다 — SYNC 의 `SummaryGenerationContext`(3-인자)는 그대로 둔다. customId·기록은 `item.jobId()` 로 만든다.

- [ ] **Step 6: 테스트 통과** / Run: `./gradlew test --tests "...SummaryBatchSubmitServiceTest"` / Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "feat(summary): batch builder — 토큰 예산 청킹·제출·기록"
```

### Task F3: Batch API Adapter (OpenAI)

**Files:**
- Create: `infrastructure/ai/openai/batch/SummaryBatchClientImpl.java`
- Create: `infrastructure/ai/openai/batch/OpenAiBatchProperties.java` (필요 시: 모델·완료 윈도우)

- [ ] **Step 1: 구현 (외부 어댑터 — 단위 테스트는 Port 모킹으로 대체, 통합은 수동/후속)**

JSONL 한 줄 = `{"custom_id": "...", "method":"POST", "url":"/v1/chat/completions", "body": {model, messages, response_format}}`. `AiSummaryClientImpl` 의 프롬프트 조립(system = `summary-generation.st`, user = `[대화 이력]...`)·`SUMMARY_RESPONSE_FORMAT` 을 **공유**하도록 포맷 로직을 재사용한다(중복 방지: 프롬프트 조립을 별도 helper 로 추출하거나 상수 공유).

핵심 메서드:
- `submit(items)`: items → JSONL 문자열 → Files API 업로드(purpose=batch) → Batch API 생성(completion_window=24h) → `BatchSubmission(batchId, inputFileId)`.
- `pollStatus(batchId)`: Batch 조회 → 상태 매핑(`completed`→COMPLETED, `failed`/`expired`/`cancelled`→FAILED, 그 외→RUNNING) + output/error file id.
- `fetchResults(status)`: output 파일 다운로드 → 줄별 `custom_id`+성공 응답 → `SummaryBatchResultItem.success`; error 파일/비2xx 줄 → 분류(429/5xx=retryable, 4xx=non-retryable) → `failure`.

> OpenAI Batch API/Files API 호출은 Spring AI 의 저수준 클라이언트 또는 `RestClient` 로 직접 호출한다. 모델·엔드포인트·완료 윈도우는 `OpenAiBatchProperties` 로 외부화. **API 스펙(엔드포인트·필드)은 구현 시점 OpenAI 문서로 확인** — claude-api 스킬은 Anthropic 전용이므로 적용 대상 아님(여기선 OpenAI).

- [ ] **Step 2: 컴파일** / Run: `./gradlew compileJava` / Expected: SUCCESS

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "feat(summary): OpenAI Batch API 어댑터(SummaryBatchClientImpl) 구현"
```

---

## Phase G — Batch collector (폴링 → 수집 → 매핑)

### Task G1: collector 서비스 (결과 매핑 + §8.3 폐기)

**Files:**
- Create: `domain/summary/service/SummaryBatchCollectService.java`
- Modify: `SummaryJobLifecycleService.java` (batch 결과 1건 반영 트랜잭션 단계)
- Modify: `OpenAiBatchRepository.java` (필요한 조회)
- Test: `src/test/java/com/readum/domain/summary/service/SummaryBatchCollectServiceTest.java`

- [ ] **Step 1: 테스트 작성 (실패 확인)**

```java
@Test
void 성공_결과는_감상문_저장하고_세션을_잠근다() {
    // SUBMITTED job(10L, session 100 ACTIVE) + result success → recordBatchSuccess: summary 저장 + session.lock + job SUCCEEDED
}
@Test
void 이미_LOCKED_세션의_결과는_폐기한다() {
    // session 100 LOCKED → 저장 안 함, job markSucceeded(무해 종료)
}
@Test
void 재시도가능_요청별_실패는_PENDING으로_되돌린다() {
    // result failure(retryable=true) → scheduleRetry, openAiBatchId null
}
@Test
void batch_통째_실패면_미완_작업을_재큐한다() {
    // pollStatus → FAILED → 그 batch 의 SUBMITTED job 들 재큐/FAILED, openai_batch markFailed
}
```

- [ ] **Step 2: 실행 — 실패 확인** / Run: `./gradlew test --tests "...SummaryBatchCollectServiceTest"` / Expected: FAIL

- [ ] **Step 3: lifecycle 단계 추가**

> **구현 설계 확정(계획 초안 대비 변경):** collector 는 customId→jobId 사전 매핑(findByOpenAiBatchId) 없이 `applyBatchResult(batch.getId(), resultItem)` 을 직접 호출한다. jobId 파싱과 batch 소속 검증을 lifecycle 메서드 안으로 캡슐화해 collector 를 단순하게 유지한다.

```java
/**
 * batch 결과 항목 하나를 해당 SummaryJob 에 적용한다. customId 형식: "summaryjob-{jobId}".
 *
 * 적용 순서:
 * 1. customId 에서 jobId 파싱 → 실패 시 warn + 건너뜀
 * 2. findByIdForUpdate 로 작업 조회
 * 3. 멱등성: status != SUBMITTED → 건너뜀(이미 처리 완료 or 재큐됨)
 * 4. batch 소속 검증: job.openAiBatchId != batchEntityId → warn + 건너뜀
 * 5. 실패 결과: 재시도 가능 여부에 따라 scheduleRetry / markFailed
 * 6. 성공 결과: 세션 ACTIVE 면 감상문 저장 + 세션 잠금 + 작업 성공, §8.3 세션 종료/부재 → 무해 종료
 */
@Transactional
public void applyBatchResult(Long batchEntityId, SummaryBatchResultItem resultItem) {
    Long jobId = parseJobId(resultItem.customId());
    if (jobId == null) {
        log.warn("감상문 batch 결과 customId 파싱 실패 — customId={}", resultItem.customId());
        return;
    }
    SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
    if (job == null) {
        log.warn("감상문 batch 결과 적용 대상 작업 없음 jobId={}", jobId);
        return;
    }
    // 멱등성: SUBMITTED 상태가 아닌 작업은 건너뛴다(이미 처리 완료 or 재큐됨).
    if (job.getStatus() != SummaryJob.Status.SUBMITTED) {
        return;
    }
    // batch 소속 검증: 이 결과 항목이 실제로 이 batch 에 속하는 작업인지 확인한다.
    if (job.getOpenAiBatchId() == null || !job.getOpenAiBatchId().equals(batchEntityId)) {
        log.warn("감상문 batch 결과 적용 — 작업이 이 batch 소속 아님 jobId={} batchEntityId={}",
                jobId, batchEntityId);
        return;
    }
    if (resultItem.failed()) {
        boolean canRetry = resultItem.retryable()
                && job.getAttemptCount() + 1 < properties.maxAttempts();
        if (canRetry) {
            LocalDateTime nextAttemptAt = properties.nextAttemptFrom(
                    LocalDateTime.now(), job.getAttemptCount());
            job.scheduleRetry(nextAttemptAt, resultItem.errorCode(), resultItem.errorMessage());
        } else {
            job.markFailed(resultItem.errorCode(), resultItem.errorMessage());
        }
        return;
    }
    // 성공 결과 — 세션이 ACTIVE 이면 감상문 저장 + 세션 잠금.
    AiChatSession session = aiChatSessionRepository.findByIdForUpdate(job.getAiChatSessionId()).orElse(null);
    if (session == null || session.getStatus() != AiChatSession.Status.ACTIVE) {
        // §8.3 세션이 이미 종료됐거나 없으면 생성 불필요 — 무해 종료.
        job.markSucceeded();
        return;
    }
    summaryRepository.save(Summary.createCompleted(
            session.getUserBookId(), session.getId(),
            resultItem.result().title(), resultItem.result().body()));
    session.lock();
    job.markSucceeded();
}

/** batch 통째 실패 — 그 batch 의 SUBMITTED 작업을 재큐/FAILED, openai_batch FAILED. */
@Transactional
public void failBatch(Long batchEntityId) {
    OpenAiBatch batch = openAiBatchRepository.findById(batchEntityId).orElse(null);
    if (batch == null) {
        log.warn("감상문 batch 실패 처리 대상 배치 없음 batchEntityId={}", batchEntityId);
        return;
    }
    batch.markFailed();
    List<SummaryJob> submittedJobs = summaryJobRepository.findByOpenAiBatchIdAndStatus(
            batchEntityId, SummaryJob.Status.SUBMITTED);
    LocalDateTime now = LocalDateTime.now();
    for (SummaryJob job : submittedJobs) {
        boolean canRetry = job.getAttemptCount() + 1 < properties.maxAttempts();
        if (canRetry) {
            LocalDateTime nextAttemptAt = properties.nextAttemptFrom(now, job.getAttemptCount());
            job.scheduleRetry(nextAttemptAt, "BATCH_FAILED", "OpenAI batch 전체 실패");
        } else {
            job.markFailed("BATCH_FAILED", "OpenAI batch 전체 실패 — 시도 상한 초과");
        }
    }
}

@Transactional
public void completeBatch(Long batchEntityId, String outputFileId, String errorFileId) {
    OpenAiBatch batch = openAiBatchRepository.findById(batchEntityId).orElse(null);
    if (batch == null) {
        log.warn("감상문 batch 완료 처리 대상 배치 없음 batchEntityId={}", batchEntityId);
        return;
    }
    batch.markCompleted(outputFileId, errorFileId);
}
```

`SummaryJobRepository` 에 추가: `List<SummaryJob> findByOpenAiBatchIdAndStatus(Long openAiBatchId, SummaryJob.Status status);`
`findByOpenAiBatchId(Long)` (상태 필터 없는 전체 조회)는 **추가하지 않는다** — collector 가 직접 jobId 를 파싱하므로 불필요.

- [ ] **Step 4: collector 서비스 구현**

collector 는 사전 매핑 없이 `applyBatchResult(batch.getId(), resultItem)` 을 직접 호출한다. jobId 파싱·검증은 lifecycle 메서드 내부에서 처리한다.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryBatchCollectService {

    private final OpenAiBatchRepository openAiBatchRepository;
    private final SummaryBatchClient batchClient;
    private final SummaryJobLifecycleService lifecycleService;

    /** SUBMITTED batch 들을 폴링·수집한다. 현재 PR: 단일 실행 가정. */
    public void collect() {
        for (OpenAiBatch batch : openAiBatchRepository.findByStatus(OpenAiBatch.Status.SUBMITTED)) {
            try {
                processOneBatch(batch);
            } catch (Exception e) {
                log.error("감상문 batch 수집 실패 batchId={}", batch.getBatchId(), e);
            }
        }
    }

    private void processOneBatch(OpenAiBatch batch) {
        SummaryBatchClient.BatchStatus status = batchClient.pollStatus(batch.getBatchId());
        switch (status.state()) {
            case RUNNING -> log.info("감상문 batch 처리 중 — batchId={}", batch.getBatchId());
            case COMPLETED -> {
                List<SummaryBatchResultItem> resultItems = batchClient.fetchResults(status);
                for (SummaryBatchResultItem resultItem : resultItems) {
                    // jobId 파싱·소속 검증은 applyBatchResult 내부에서 처리한다.
                    lifecycleService.applyBatchResult(batch.getId(), resultItem);
                }
                lifecycleService.completeBatch(batch.getId(), status.outputFileId(), status.errorFileId());
                log.info("감상문 batch 수집 완료 batchId={} 항목수={}", batch.getBatchId(), resultItems.size());
            }
            case FAILED -> {
                log.error("감상문 batch 실패(OpenAI 측) batchId={}", batch.getBatchId());
                lifecycleService.failBatch(batch.getId());
            }
        }
    }
}
```

- [ ] **Step 5: 테스트 통과** / Run: `./gradlew test --tests "...SummaryBatchCollectServiceTest"` / Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat(summary): batch collector — 결과 매핑·세션 LOCK·폐기·재큐"
```

---

## Phase H — 스케줄러 배선 + 설정

### Task H1: builder/collector 스케줄러 + yml

**Files:**
- Create: `infrastructure/summary/scheduler/SummaryBatchSubmitScheduler.java`, `SummaryBatchCollectScheduler.java`
- Modify: `infrastructure/aiChat/scheduler/SummarySchedulerConfig.java` (builder 풀)
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: builder 디스패처 (다중, 동시성 제한)**

`SummaryJobDispatcher` 패턴을 따른다(슬롯 = builderConcurrency - running). 각 슬롯이 `submitService.submitOneChunk()` 를 청크 없을 때까지 반복.

```java
@Scheduled(fixedDelayString = "${summary-batch.submit-interval-ms}")
public void dispatch() {
    int slots = batchProperties.builderConcurrency() - running.get();
    for (int i = 0; i < slots; i++) {
        running.incrementAndGet();
        summaryBatchExecutor.execute(() -> {
            try { while (submitService.submitOneChunk()) { /* 계속 */ } }
            catch (Exception e) { log.error("감상문 batch builder 오류", e); }
            finally { running.decrementAndGet(); }
        });
    }
}
```

- [ ] **Step 2: collector 스케줄러 (단일)**

```java
@Scheduled(fixedDelayString = "${summary-batch.collect-interval-ms}")
public void collect() {
    try { collectService.collect(); }
    catch (Exception e) { log.error("감상문 batch collector 오류", e); }
}
```

> 단일 실행 보장: collector 는 풀에 던지지 않고 스케줄러 스레드에서 직접 실행. (다중 collector·SKIP LOCKED 는 후속 PR #86)

- [ ] **Step 3: builder 전용 Executor 빈 추가** (`SummarySchedulerConfig` 에 `summaryBatchExecutor`, core=2/max=4 등)

- [ ] **Step 4: yml 추가**

```yaml
summary-batch:
  builder-concurrency: 2
  chunk-token-limit: 1500000      # per-batch 토큰 안전 상한(우리 tier 한도 - 마진)
  max-jobs-per-batch: 500
  submit-interval-ms: 5000
  collect-interval-ms: 60000
  build-lease-seconds: 300        # BATCH_BUILDING 점유 시한(업로드+제출 최악 소요 초과)
```

> 정적 보장(§7.2): `builder-concurrency × chunk-token-limit × 안전계수 ≤ 계정 enqueued-token 한도 - 마진` 이 성립하도록 값을 잡는다. 위 값은 예시 — 운영 tier 한도에 맞춰 조정.

- [ ] **Step 5: 전체 컴파일·기동 검증**

Run: `./gradlew compileJava compileTestJava`
Expected: SUCCESS

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat(summary): batch builder/collector 스케줄러 배선 + 설정"
```

---

## Phase I — 통합/회귀 테스트 (요청사항 8)

### Task I1: 회수·차단·중복방지 통합 테스트

**Files:**
- Create: `src/test/java/com/readum/domain/summary/service/SummaryJobReclaimIntegrationTest.java`
- Modify: 기존 `SummaryDraftServiceTest` 에 중복 방지 케이스 보강

- [ ] **Step 1: 테스트 작성 (실패 확인)**

```java
// 회수: BATCH_BUILDING 점유 만료 → reclaimOrphans → PENDING
@Test
void BATCH_BUILDING_점유_만료시_PENDING으로_회수된다() {
    repository.save(SummaryJobFixture.persistedBatchBuilding(null, 100L, "o", LocalDateTime.now().minusMinutes(1)));
    lifecycleService.reclaimOrphans(100);
    // 재조회 → status PENDING
}

// 미회수: SUBMITTED 는 회수 대상이 아님
@Test
void SUBMITTED는_회수_대상이_아니다() {
    SummaryJob submitted = repository.save(SummaryJobFixture.persistedSubmitted(null, 100L, 1L));
    lifecycleService.reclaimOrphans(100);
    // 재조회 → status 여전히 SUBMITTED
}

// 중복 방지: 활성 작업 존재 시 수동 요청은 새 작업을 만들지 않음
@Test
void 활성작업_존재시_수동요청은_새_작업을_만들지_않는다() {
    // BATCH 활성 작업 존재 → SummaryDraftService.execute → ConflictException(SUMMARY_IN_PROGRESS)
}
```

- [ ] **Step 2~4: 실행/구현확인/통과** — 위 Phase 들이 동작을 제공하므로 테스트만 추가하면 통과해야 한다. 실패 시 해당 Phase 로 돌아가 수정.

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryJobReclaimIntegrationTest"`
Expected: PASS

- [ ] **Step 5: 채팅 차단 쿼리 테스트는 Task D1 에 이미 포함** (확인만)

- [ ] **Step 6: 전체 테스트**

Run: `./gradlew test`
Expected: PASS (전체)

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "test(summary): 회수 범위·SUBMITTED 미회수·차단·중복방지 통합 테스트"
```

---

## 마무리

- [ ] **폐기 개념 부재 확인 (스펙 §9)**: 재요약/증분 추적 잔재가 없는지 확인. 있으면 제거.
  - Run: `grep -rni "REFRESH\|job_type\|jobType\|chat_summary_state\|last_summarized_message" src/main`
  - Expected: 감상문 재요약 관련 매치 없음(이 브랜치는 이미 1:1·LOCKED 모델). 매치가 있으면 제거 후 커밋.
- [ ] **전체 검증**: `./gradlew clean build`
- [ ] **verify 서브에이전트**: `/verify` (TODO 잔존·테스트·@Value·테스트 클래스 대응 점검)
- [ ] **코드 리뷰**: Codex(readum-review) 통과 전 머지 금지
- [ ] **PR**: `/pr` (base=dev). 후속 안정성 보강은 이슈 #86 로 분리됨을 PR 본문에 명시.

> **후속 PR(이슈 #86)로 미룬 것 — 이 PR 에 넣지 말 것**: openai_batch 행 선생성, 계정 토큰 back-pressure 정교화(DB 락 예약), collector 다중 실행 SKIP LOCKED/COLLECTING, 장기 SUBMITTED UX 검증.
