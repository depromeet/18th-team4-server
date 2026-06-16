# 감상문 생성 종료 모델 + 작업 큐 핵심 흐름 (Plan 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 감상문 자동 생성을 "스케줄러 직접 호출" 에서 "DB 작업 큐(`summary_job`) + 워커 + 회수기" 로 바꾸고, 동시에 세션을 종료(LOCKED) 모델(세션:감상문 1:1)로 전환한다.

**Architecture:** 스케줄러는 대상 세션을 찾아 PENDING 작업만 적재한다. 워커 스레드들이 `FOR UPDATE SKIP LOCKED` 로 작업을 하나씩 선점(PROCESSING + claim token + 5분 lease)해 OpenAI 를 호출하고, 성공 시 한 트랜잭션에서 Summary 저장 + 세션 LOCKED + 작업 SUCCEEDED 를, 실패 시 백오프 재시도를 기록한다. 멈춘(고아) 작업은 회수기가 lease 만료로 감지해 PENDING 으로 되돌린다. "생성 중 차단" 은 세션 상태가 아니라 "유효 PROCESSING 작업 존재" 로 도출한다.

**Tech Stack:** Spring Boot 4 / Java 25 / JPA(Hibernate) / MySQL(dev)·H2(test) / Lombok / JUnit5 + Mockito + AssertJ. (rate limiter·circuit breaker 는 Plan 2, `AiChatSessionTitleService` 의 TransactionTemplate 제거는 Plan 3.)

**선행 설계:** `docs/superpowers/specs/2026-06-16-summary-generation-recovery-design.md`

---

## 범위 메모

- **Plan 1 의 실패 처리는 단순 2분류**(재시도 가능 vs 상한 초과 FAILED)로 둔다. 모든 예외를 `maxAttempts` 까지 백오프 재시도하고(작업 유실 0 보장), `TooManyRequestsException` 은 `Retry-After` 를 존중한다. **4xx 비재시도 즉시 실패 + quota breaker 는 Plan 2** 에서 추가한다.
- **SKIP LOCKED 동시 선점 동작은 MySQL(dev)에서 성립**한다. H2(test)는 `SKIP LOCKED` 힌트를 무시할 수 있어, DAO 테스트는 선점 쿼리의 *선별·정렬·전이* 만 검증하고 동시-skip 자체는 단언하지 않는다(주석으로 명시).

## 파일 구조

신규(main): `model/summary/entity/SummaryJob.java`, `model/summary/repository/SummaryJobRepository.java`, `domain/summary/dto/SummaryGenerationContext.java`, `domain/summary/service/EnqueueSummaryJobService.java`, `domain/summary/service/SummaryJobTxService.java`, `domain/summary/service/SummaryGenerationWorker.java`, `domain/summary/config/SummaryJobProperties.java`, `infrastructure/summary/scheduler/SummaryJobDispatcher.java`, `infrastructure/summary/scheduler/SummaryJobReaper.java`

신규(test): `model/summary/entity/SummaryJobFixture.java` + 각 단위/DAO 테스트

수정: `model/aiChat/entity/AiChatSession.java`, `model/summary/entity/Summary.java`, `model/summary/repository/SummaryRepository.java`, `model/aiChat/repository/AiChatSessionRepository.java`, `domain/aiChat/service/SummaryDraftService.java`, `domain/aiChat/service/policy/SummaryDraftPolicy.java`, `domain/aiChat/dto/SummaryDraftEligibility.java`, `domain/aiChat/exception/AiChatErrorCode.java`, `domain/aiChat/service/AiChatMessagePersistService.java`, `domain/summary/service/SummarySearchService.java`, `infrastructure/aiChat/scheduler/SummaryScheduler.java`, `presentation/controller/aiChat/AiChatController.java`, `src/test/.../AiChatSessionFixture.java`, `application.yml`

---

## Task 1: SummaryJob 엔티티

**Files:**
- Create: `src/main/java/com/readum/model/summary/entity/SummaryJob.java`
- Create: `src/test/java/com/readum/model/summary/entity/SummaryJobFixture.java`
- Test: `src/test/java/com/readum/model/summary/entity/SummaryJobTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/readum/model/summary/entity/SummaryJobTest.java`:

```java
package com.readum.model.summary.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryJobTest {

    @Test
    void createPending_은_PENDING_활성작업으로_시작한다() {
        SummaryJob job = SummaryJob.createPending(42L);

        assertThat(job.getAiChatSessionId()).isEqualTo(42L);
        assertThat(job.getActiveSessionId()).isEqualTo(42L);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
    }

    @Test
    void claim_은_PROCESSING_으로_바뀌고_소유자와_lease를_설정한다() {
        SummaryJob job = SummaryJob.createPending(1L);
        LocalDateTime until = LocalDateTime.now().plusMinutes(5);

        job.claim("owner-1", until);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING);
        assertThat(job.getLockOwner()).isEqualTo("owner-1");
        assertThat(job.getLockedUntil()).isEqualTo(until);
        assertThat(job.isOwnedBy("owner-1")).isTrue();
        assertThat(job.isOwnedBy("owner-2")).isFalse();
    }

    @Test
    void markSucceeded_는_활성해제하고_SUCCEEDED로_만든다() {
        SummaryJob job = SummaryJob.createPending(1L);
        job.claim("owner-1", LocalDateTime.now().plusMinutes(5));

        job.markSucceeded();

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
        assertThat(job.getActiveSessionId()).isNull();
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
    }

    @Test
    void scheduleRetry_는_시도횟수를_올리고_PENDING으로_되돌린다() {
        SummaryJob job = SummaryJob.createPending(1L);
        job.claim("owner-1", LocalDateTime.now().plusMinutes(5));
        LocalDateTime next = LocalDateTime.now().plusMinutes(1);

        job.scheduleRetry(next, "AI_PROVIDER_TRANSIENT", "일시 오류");

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(next);
        assertThat(job.getActiveSessionId()).isEqualTo(1L); // 재시도 동안에도 활성 유지
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLastErrorCode()).isEqualTo("AI_PROVIDER_TRANSIENT");
    }

    @Test
    void markFailed_는_활성해제하고_FAILED로_만든다() {
        SummaryJob job = SummaryJob.createPending(1L);
        job.claim("owner-1", LocalDateTime.now().plusMinutes(5));

        job.markFailed("AI_PROVIDER_ERROR", "회복 불가");

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.FAILED);
        assertThat(job.getActiveSessionId()).isNull();
        assertThat(job.getLastErrorMessage()).isEqualTo("회복 불가");
    }

    @Test
    void releaseAfterOrphan_은_즉시_재선점가능한_PENDING으로_되돌린다() {
        SummaryJob job = SummaryJob.createPending(1L);
        job.claim("owner-1", LocalDateTime.now().minusMinutes(1)); // lease 만료된 상태 가정

        job.releaseAfterOrphan(LocalDateTime.now());

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
        assertThat(job.getActiveSessionId()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.readum.model.summary.entity.SummaryJobTest"`
Expected: 컴파일 실패 (`SummaryJob` 없음)

- [ ] **Step 3: 엔티티 구현**

`src/main/java/com/readum/model/summary/entity/SummaryJob.java`:

```java
package com.readum.model.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 감상문 생성 작업 큐의 한 행. "이 세션은 감상문을 만들어야 한다" 는 의도를 영속화한다.
 * 작업은 잃으면 복구 불가하므로 DB 에 영속한다. (서킷브레이커와 달리 임시 판단이 아님)
 * 상태: PENDING(처리 대기) → PROCESSING(워커 점유) → SUCCEEDED / FAILED.
 * active_session_id: 미완료(PENDING/PROCESSING) 동안만 세션 id, 완료/실패 시 NULL.
 *   → unique 제약으로 "세션당 활성 작업 1개" 를 보장한다.
 */
@Getter
@Entity
@Table(
        name = "summary_job",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_summary_job_active_session", columnNames = "active_session_id")
        },
        indexes = {
                @Index(name = "idx_summary_job_claim", columnList = "status, next_attempt_at"),
                @Index(name = "idx_summary_job_session", columnList = "ai_chat_session_id, status")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class SummaryJob {

    public enum Status {
        PENDING, PROCESSING, SUCCEEDED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ai_chat_session_id", nullable = false)
    private Long aiChatSessionId;

    @Column(name = "active_session_id")
    private Long activeSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "lock_owner", length = 36)
    private String lockOwner;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 새 작업은 즉시 처리 가능한 PENDING 으로 시작한다. */
    public static SummaryJob createPending(Long aiChatSessionId) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                null, aiChatSessionId, aiChatSessionId, Status.PENDING,
                null, null, 0, now, null, null, now, now
        );
    }

    /** 워커가 작업을 점유한다. owner 는 이 선점만의 토큰(UUID), lockedUntil 은 lease 만료 시각. */
    public void claim(String owner, LocalDateTime lockedUntil) {
        this.status = Status.PROCESSING;
        this.lockOwner = owner;
        this.lockedUntil = lockedUntil;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isOwnedBy(String owner) {
        return this.status == Status.PROCESSING && owner != null && owner.equals(this.lockOwner);
    }

    public void markSucceeded() {
        this.status = Status.SUCCEEDED;
        this.activeSessionId = null;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** 일시 실패 — 백오프 후 다시 처리하도록 PENDING 으로. 활성(active_session_id) 은 유지. */
    public void scheduleRetry(LocalDateTime nextAttemptAt, String errorCode, String errorMessage) {
        this.status = Status.PENDING;
        this.attemptCount += 1;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.nextAttemptAt = nextAttemptAt;
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    /** 회복 불가 또는 시도 상한 초과 — 종료. 활성 해제로 다음 날 새 작업 적재를 허용. */
    public void markFailed(String errorCode, String errorMessage) {
        this.status = Status.FAILED;
        this.activeSessionId = null;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    /** 회수기 전용 — lease 만료된 고아를 즉시 재선점 가능한 PENDING 으로 되돌린다. */
    public void releaseAfterOrphan(LocalDateTime now) {
        this.status = Status.PENDING;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.nextAttemptAt = now;
        this.updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 4: 픽스처 작성**

`src/test/java/com/readum/model/summary/entity/SummaryJobFixture.java`:

```java
package com.readum.model.summary.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link SummaryJob} 을 만드는 명명 팩토리. 같은 패키지의 package-private 전체필드 생성자 호출.
 */
@TestOnly
public final class SummaryJobFixture {

    private SummaryJobFixture() {
    }

    /** 저장되어 처리 대기(PENDING)인 작업. nextAttemptAt 으로 처리 가능 시점을 제어한다. */
    public static SummaryJob persistedPending(Long id, Long sessionId, LocalDateTime nextAttemptAt) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.Status.PENDING,
                null, null, 0, nextAttemptAt, null, null, now, now
        );
    }

    /** 저장되어 점유(PROCESSING)된 작업. lockedUntil 로 lease 만료 여부를 제어한다(회수기 테스트용). */
    public static SummaryJob persistedProcessing(
            Long id, Long sessionId, String owner, LocalDateTime lockedUntil
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.Status.PROCESSING,
                owner, lockedUntil, 0, now, null, null, now, now
        );
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.model.summary.entity.SummaryJobTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/readum/model/summary/entity/SummaryJob.java \
        src/test/java/com/readum/model/summary/entity/SummaryJobFixture.java \
        src/test/java/com/readum/model/summary/entity/SummaryJobTest.java
git commit -m "feat(summary): 감상문 작업 큐 엔티티 SummaryJob 추가"
```

---

## Task 2: SummaryJobRepository (선점·고아·활성작업 조회)

**Files:**
- Create: `src/main/java/com/readum/model/summary/repository/SummaryJobRepository.java`
- Test: `src/test/java/com/readum/model/summary/repository/SummaryJobRepositoryTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/readum/model/summary/repository/SummaryJobRepositoryTest.java`:

```java
package com.readum.model.summary.repository;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SummaryJobRepositoryTest {

    @Autowired
    private SummaryJobRepository summaryJobRepository;

    private static long sessionSeq = 700_000L;

    private static synchronized long nextSessionId() {
        return sessionSeq++;
    }

    @Test
    void findClaimable_은_처리시점이_지난_PENDING만_nextAttemptAt_오름차순으로_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        SummaryJob ready = summaryJobRepository.save(
                SummaryJobFixture.persistedPending(null, nextSessionId(), now.minusSeconds(10)));
        // 아직 처리 시점이 안 된 작업은 제외되어야 한다
        summaryJobRepository.save(
                SummaryJobFixture.persistedPending(null, nextSessionId(), now.plusMinutes(10)));

        List<SummaryJob> claimable =
                summaryJobRepository.findClaimable(SummaryJob.Status.PENDING, now, PageRequest.of(0, 10));

        assertThat(claimable).extracting(SummaryJob::getId).contains(ready.getId());
        assertThat(claimable).allMatch(job -> !job.getNextAttemptAt().isAfter(now));
    }

    @Test
    void findOrphaned_은_lease가_만료된_PROCESSING만_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        SummaryJob orphan = summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, nextSessionId(), "dead", now.minusMinutes(1)));
        // lease 가 아직 유효한 PROCESSING 은 제외
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, nextSessionId(), "alive", now.plusMinutes(5)));

        List<SummaryJob> orphans = summaryJobRepository.findOrphaned(now, PageRequest.of(0, 10));

        assertThat(orphans).extracting(SummaryJob::getId).contains(orphan.getId());
        assertThat(orphans).allMatch(job -> job.getLockedUntil().isBefore(now));
    }

    @Test
    void existsActiveProcessingJob_은_유효_lease의_PROCESSING이_있을때만_true() {
        LocalDateTime now = LocalDateTime.now();
        long withValid = nextSessionId();
        long withExpired = nextSessionId();
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, withValid, "w", now.plusMinutes(5)));
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, withExpired, "w", now.minusMinutes(1)));

        assertThat(summaryJobRepository.existsActiveProcessingJob(withValid, now)).isTrue();
        assertThat(summaryJobRepository.existsActiveProcessingJob(withExpired, now)).isFalse();
        assertThat(summaryJobRepository.existsActiveProcessingJob(nextSessionId(), now)).isFalse();
    }

    @Test
    void active_session_id_는_세션당_하나만_허용한다() {
        long sessionId = nextSessionId();
        summaryJobRepository.saveAndFlush(SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        summaryJobRepository.saveAndFlush(
                                SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.readum.model.summary.repository.SummaryJobRepositoryTest"`
Expected: 컴파일 실패 (`SummaryJobRepository` 없음)

- [ ] **Step 3: 리포지토리 구현**

`src/main/java/com/readum/model/summary/repository/SummaryJobRepository.java`:

```java
package com.readum.model.summary.repository;

import com.readum.model.summary.entity.SummaryJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SummaryJobRepository extends JpaRepository<SummaryJob, Long> {

    /**
     * 처리 가능한 PENDING 작업을 선점 후보로 조회한다.
     * PESSIMISTIC_WRITE + lock timeout -2(Hibernate SKIP LOCKED): 다른 워커가 이미 잠근 행은 건너뛴다.
     * 주의: SKIP LOCKED 동시-skip 동작은 MySQL 에서 성립하며 H2 에서는 무시될 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.status = :status
               and summaryJob.nextAttemptAt <= :now
             order by summaryJob.nextAttemptAt asc
                    , summaryJob.id asc
            """)
    List<SummaryJob> findClaimable(
            @Param("status") SummaryJob.Status status,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /** lease 가 만료된 PROCESSING(고아) 작업. 회수기가 PENDING 으로 되돌릴 대상. */
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
               and summaryJob.lockedUntil < :now
             order by summaryJob.lockedUntil asc
            """)
    List<SummaryJob> findOrphaned(@Param("now") LocalDateTime now, Pageable pageable);

    /** 기록/회수 시 행 단위 직렬화를 위한 비관적 락 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select summaryJob from SummaryJob summaryJob where summaryJob.id = :id")
    Optional<SummaryJob> findByIdForUpdate(@Param("id") Long id);

    /** "지금 생성 중" 판정 — 유효 lease 의 PROCESSING 작업이 그 세션에 있는가. */
    @Query("""
            select case when count(summaryJob) > 0 then true else false end
              from SummaryJob summaryJob
             where summaryJob.aiChatSessionId = :sessionId
               and summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
               and summaryJob.lockedUntil > :now
            """)
    boolean existsActiveProcessingJob(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);

    /** 세션에 미완료(활성) 작업이 이미 있는지 — 적재 멱등성 사전 확인용. */
    boolean existsByActiveSessionId(Long activeSessionId);
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.model.summary.repository.SummaryJobRepositoryTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/readum/model/summary/repository/SummaryJobRepository.java \
        src/test/java/com/readum/model/summary/repository/SummaryJobRepositoryTest.java
git commit -m "feat(summary): SummaryJobRepository 선점/고아/활성작업 조회 추가"
```

---

## Task 3: SummaryJobProperties + application.yml

**Files:**
- Create: `src/main/java/com/readum/domain/summary/config/SummaryJobProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Properties 작성**

`src/main/java/com/readum/domain/summary/config/SummaryJobProperties.java`:

```java
package com.readum.domain.summary.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 감상문 작업 큐의 비즈니스 룰(환경 무관 상수). 잘못된 값(0/음수)은 부팅 시 차단(@Validated).
 */
@Validated
@ConfigurationProperties(prefix = "summary-job")
public record SummaryJobProperties(
        @Positive int poolSize,
        @Positive long dispatchIntervalMs,
        @Positive long reaperIntervalMs,
        @Positive long leaseSeconds,
        @Positive int maxAttempts,
        @Positive long baseBackoffSeconds
) {

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    /** 지수 백오프: base * 2^attemptCount (attemptCount = 지금까지의 시도 횟수). */
    public LocalDateTime nextAttemptFrom(LocalDateTime now, int attemptCount) {
        long seconds = baseBackoffSeconds * (1L << Math.min(attemptCount, 10));
        return now.plusSeconds(seconds);
    }
}
```

- [ ] **Step 2: application.yml 에 값 추가**

`src/main/resources/application.yml` 끝에 추가:

```yaml
summary-job:
  pool-size: 3
  dispatch-interval-ms: 2000
  reaper-interval-ms: 60000
  lease-seconds: 300        # 5분 lease
  max-attempts: 5
  base-backoff-seconds: 60  # 1분 → 2분 → 4분 …
```

- [ ] **Step 3: 부팅 확인 (Properties 바인딩)**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (등록은 기존 `@ConfigurationPropertiesScan` 이 처리)

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/readum/domain/summary/config/SummaryJobProperties.java src/main/resources/application.yml
git commit -m "feat(summary): 작업 큐 설정값(SummaryJobProperties) 추가"
```

---

## Task 4: EnqueueSummaryJobService (멱등 적재)

**Files:**
- Create: `src/main/java/com/readum/domain/summary/service/EnqueueSummaryJobService.java`
- Test: `src/test/java/com/readum/domain/summary/service/EnqueueSummaryJobServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/readum/domain/summary/service/EnqueueSummaryJobServiceTest.java`:

```java
package com.readum.domain.summary.service;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.SummaryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EnqueueSummaryJobServiceTest {

    @Mock
    private SummaryJobRepository summaryJobRepository;

    @InjectMocks
    private EnqueueSummaryJobService enqueueSummaryJobService;

    @Test
    void 활성작업이_없으면_PENDING_작업을_저장한다() {
        given(summaryJobRepository.existsByActiveSessionId(1L)).willReturn(false);

        enqueueSummaryJobService.execute(1L);

        verify(summaryJobRepository).save(any(SummaryJob.class));
    }

    @Test
    void 활성작업이_이미_있으면_저장하지_않는다() {
        given(summaryJobRepository.existsByActiveSessionId(1L)).willReturn(true);

        enqueueSummaryJobService.execute(1L);

        verify(summaryJobRepository, never()).save(any());
    }

    @Test
    void 동시적재로_unique위반이_나도_예외를_삼킨다() {
        given(summaryJobRepository.existsByActiveSessionId(1L)).willReturn(false);
        given(summaryJobRepository.save(any())).willThrow(new DataIntegrityViolationException("dup"));

        assertThatCode(() -> enqueueSummaryJobService.execute(1L)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.EnqueueSummaryJobServiceTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 서비스 구현**

`src/main/java/com/readum/domain/summary/service/EnqueueSummaryJobService.java`:

```java
package com.readum.domain.summary.service;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.SummaryJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션에 대한 감상문 생성 작업을 멱등하게 적재한다.
 * 같은 세션에 이미 활성 작업이 있으면 새로 만들지 않는다(active_session_id unique).
 * 동시 적재로 unique 위반이 나도 "이미 예약됨" 으로 보고 조용히 무시한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnqueueSummaryJobService {

    private final SummaryJobRepository summaryJobRepository;

    @Transactional
    public void execute(Long sessionId) {
        if (summaryJobRepository.existsByActiveSessionId(sessionId)) {
            return;
        }
        try {
            summaryJobRepository.save(SummaryJob.createPending(sessionId));
        } catch (DataIntegrityViolationException e) {
            log.debug("감상문 작업 적재 경합 — 이미 활성 작업 존재 sessionId={}", sessionId);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.EnqueueSummaryJobServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/readum/domain/summary/service/EnqueueSummaryJobService.java \
        src/test/java/com/readum/domain/summary/service/EnqueueSummaryJobServiceTest.java
git commit -m "feat(summary): 멱등 작업 적재 EnqueueSummaryJobService 추가"
```

---

## Task 5: 생성 컨텍스트 DTO + AiChatMessageRepository 의존 확인

**Files:**
- Create: `src/main/java/com/readum/domain/summary/dto/SummaryGenerationContext.java`

- [ ] **Step 1: DTO 작성** (테스트 불필요 — 순수 데이터 보관 record)

`src/main/java/com/readum/domain/summary/dto/SummaryGenerationContext.java`:

```java
package com.readum.domain.summary.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

/**
 * 워커가 한 작업을 처리할 때, "준비" 트랜잭션이 모아 넘기는 생성 입력.
 * 세션 식별자 + 감상문 귀속용 userBookId + 요약에 넣을 전체 대화.
 */
public record SummaryGenerationContext(
        Long sessionId,
        Long userBookId,
        List<AiChatMessage> messages
) {
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/readum/domain/summary/dto/SummaryGenerationContext.java
git commit -m "feat(summary): 생성 입력 DTO SummaryGenerationContext 추가"
```

---

## Task 6: AiChatSession 종료 모델 — `lock()` 의미 확정 (LOCKED=영구)

> 이 시점에는 `unlock()` 을 아직 지우지 않는다(아직 `SummaryDraftService` 가 호출 중이라 컴파일이 깨짐). 의미만 주석으로 확정하고, `unlock()` 제거는 Task 12(생성 경로 전환) 이후 Task 13에서 한다.

**Files:**
- Modify: `src/main/java/com/readum/model/aiChat/entity/AiChatSession.java`
- Test: `src/test/java/com/readum/model/aiChat/entity/AiChatSessionTest.java` (없으면 생성)

- [ ] **Step 1: 의미 확정 테스트 작성**

`src/test/java/com/readum/model/aiChat/entity/AiChatSessionTest.java` 에 추가(없으면 생성):

```java
package com.readum.model.aiChat.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatSessionTest {

    @Test
    void lock_은_세션을_영구_잠금_LOCKED로_만든다() {
        AiChatSession session = AiChatSession.create(1L);

        session.lock();

        assertThat(session.isLocked()).isTrue();
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.LOCKED);
    }
}
```

- [ ] **Step 2: 테스트 실행 (이미 통과할 수 있음 — lock() 시그니처는 그대로)**

Run: `./gradlew test --tests "com.readum.model.aiChat.entity.AiChatSessionTest"`
Expected: PASS (lock() 동작은 동일, 의미만 문서화)

- [ ] **Step 3: 주석으로 의미 재정의**

`AiChatSession.java` 의 `lock()` Javadoc 을 교체:

```java
    /**
     * 감상문 생성에 성공하면 세션을 영구 잠근다(LOCKED). 이후 이 세션에서는 대화할 수 없다.
     * (구 모델의 "생성 중 일시 잠금" 이 아니라 "완료 후 종료" 를 뜻한다 — 되돌리지 않는다.)
     */
    public void lock() {
        this.status = Status.LOCKED;
        this.updatedAt = LocalDateTime.now();
    }
```

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/readum/model/aiChat/entity/AiChatSession.java \
        src/test/java/com/readum/model/aiChat/entity/AiChatSessionTest.java
git commit -m "refactor(aiChat): AiChatSession.lock() 을 영구 잠금(종료) 의미로 확정"
```

---

## Task 7: Summary 1:1 제약 + 단건 조회

**Files:**
- Modify: `src/main/java/com/readum/model/summary/entity/Summary.java`
- Modify: `src/main/java/com/readum/model/summary/repository/SummaryRepository.java`
- Test: `src/test/java/com/readum/model/summary/repository/SummaryRepositoryTest.java` (추가)

- [ ] **Step 1: 실패 테스트 추가**

`SummaryRepositoryTest.java` 에 메서드 추가:

```java
    @Test
    void findByAiChatSessionId_는_세션의_감상문_단건을_반환한다() {
        long sessionId = nextSessionId();
        Long userBookId = nextUserBookId();
        summaryRepository.save(SummaryFixture.persistedSummary(null, userBookId, sessionId, "제목", "본문"));

        Optional<Summary> found = summaryRepository.findByAiChatSessionId(sessionId);

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("제목");
    }

    @Test
    void 한_세션에_감상문은_하나만_저장된다() {
        long sessionId = nextSessionId();
        Long userBookId = nextUserBookId();
        summaryRepository.saveAndFlush(SummaryFixture.persistedSummary(null, userBookId, sessionId, "t1", "b1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        summaryRepository.saveAndFlush(
                                SummaryFixture.persistedSummary(null, userBookId, sessionId, "t2", "b2")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.readum.model.summary.repository.SummaryRepositoryTest"`
Expected: FAIL (`findByAiChatSessionId` 없음 / unique 없음)

- [ ] **Step 3: Summary 에 unique 제약 추가**

`Summary.java` 의 `@Table` 을 교체(기존 인덱스 유지 + unique 추가):

```java
@Table(
        name = "summary",
        uniqueConstraints = {
                @jakarta.persistence.UniqueConstraint(
                        name = "uk_summary_session", columnNames = "ai_chat_session_id")
        },
        indexes = {
                @Index(name = "idx_summary_user_book", columnList = "user_book_id"),
                @Index(name = "idx_summary_session", columnList = "ai_chat_session_id")
        }
)
```

- [ ] **Step 4: SummaryRepository 에 단건 조회 추가**

`SummaryRepository.java` 에 메서드 추가:

```java
    /** 세션의 감상문(1:1). 종료 모델에서 세션당 최대 한 행이다. */
    java.util.Optional<com.readum.model.summary.entity.Summary> findByAiChatSessionId(Long aiChatSessionId);
```

> 기존 `findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc` 는 호출부(Task 14의 SummarySearchService)를 단건 조회로 바꾼 뒤 제거 대상이지만, 지금은 남겨 컴파일을 유지한다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.model.summary.repository.SummaryRepositoryTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/readum/model/summary/entity/Summary.java \
        src/main/java/com/readum/model/summary/repository/SummaryRepository.java \
        src/test/java/com/readum/model/summary/repository/SummaryRepositoryTest.java
git commit -m "feat(summary): 세션:감상문 1:1 unique 제약 + 단건 조회 추가"
```

---

## Task 8: SummaryJobTxService (선점·준비·기록, 펜싱)

**Files:**
- Create: `src/main/java/com/readum/domain/summary/service/SummaryJobTxService.java`
- Test: `src/test/java/com/readum/domain/summary/service/SummaryJobTxServiceTest.java`

이 서비스는 워커가 호출하는 **각 트랜잭션 단계**다(비-TX 오케스트레이터는 Task 9). `findByIdForUpdate` 로 작업 행을 잠그고 `lock_owner` 일치를 확인해 펜싱한다.

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/readum/domain/summary/service/SummaryJobTxServiceTest.java`:

```java
package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryJobTxServiceTest {

    @Mock private SummaryJobRepository summaryJobRepository;
    @Mock private AiChatSessionRepository aiChatSessionRepository;
    @Mock private AiChatMessageRepository aiChatMessageRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private SummaryJobProperties properties;

    @InjectMocks private SummaryJobTxService summaryJobTxService;

    @Test
    void recordSuccess_는_소유권_일치시_감상문저장_세션잠금_작업성공을_한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1L, 5L, 2, 600, "제목");
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(1L)).willReturn(Optional.of(session));

        summaryJobTxService.recordSuccess(10L, "owner-1", 1L, new SummaryDraftResult("제목", "본문"));

        verify(summaryRepository).save(any(Summary.class));
        assertThat(session.isLocked()).isTrue();
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
    }

    @Test
    void recordSuccess_는_소유권_불일치시_아무것도_하지_않는다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "other-owner", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));

        summaryJobTxService.recordSuccess(10L, "owner-1", 1L, new SummaryDraftResult("제목", "본문"));

        verify(summaryRepository, never()).save(any());
        verify(aiChatSessionRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void recordFailure_는_상한미만이면_재시도를_예약한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(properties.maxAttempts()).willReturn(5);
        given(properties.nextAttemptFrom(any(), anyInt())).willReturn(LocalDateTime.now().plusMinutes(1));

        summaryJobTxService.recordFailure(10L, "owner-1", true, "AI_PROVIDER_TRANSIENT", "일시", null);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void recordFailure_는_재시도불가면_즉시_FAILED로_만든다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));

        summaryJobTxService.recordFailure(10L, "owner-1", false, "AI_PROVIDER_ERROR", "회복불가", null);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.FAILED);
    }

    @Test
    void prepareGeneration_은_세션이_ACTIVE가_아니면_작업을_성공처리하고_null을_반환한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        AiChatSession locked = AiChatSessionFixture.persistedActiveSession(1L, 5L, 2, 600, "제목");
        locked.lock(); // 이미 종료된 세션
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(1L)).willReturn(Optional.of(locked));

        SummaryGenerationContext context = summaryJobTxService.prepareGeneration(10L, "owner-1");

        assertThat(context).isNull();
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
    }

    @Test
    void prepareGeneration_은_ACTIVE_세션이면_대화를_모아_컨텍스트를_반환한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1L, 7L, 2, 600, "제목");
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(1L)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(1L))
                .willReturn(List.of());

        SummaryGenerationContext context = summaryJobTxService.prepareGeneration(10L, "owner-1");

        assertThat(context).isNotNull();
        assertThat(context.sessionId()).isEqualTo(1L);
        assertThat(context.userBookId()).isEqualTo(7L);
    }
}
```

(상단 import 에 `import static org.mockito.ArgumentMatchers.anyInt;` 추가)

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryJobTxServiceTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 서비스 구현**

`src/main/java/com/readum/domain/summary/service/SummaryJobTxService.java`:

```java
package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 한 작업 처리의 트랜잭션 단계들. 비-TX 오케스트레이터(SummaryGenerationWorker)가 순서대로 호출한다.
 * 모든 변경 메서드는 작업 행을 비관적 락으로 잡고 lock_owner 일치(펜싱)를 확인한 뒤에만 반영한다 —
 * lease 만료로 재선점된 작업을 늦게 돌아온 옛 워커가 건드리지 못하게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryJobTxService {

    private final SummaryJobRepository summaryJobRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryJobProperties properties;

    /**
     * 처리 대상 작업을 하나 선점한다. SKIP LOCKED 로 다른 워커와 겹치지 않는다.
     * owner 는 이 선점만의 토큰. 반환된 작업 id 를 워커가 이후 단계에 넘긴다.
     */
    @Transactional
    public Long claimOne(String owner) {
        List<SummaryJob> candidates = summaryJobRepository.findClaimable(
                SummaryJob.Status.PENDING, LocalDateTime.now(),
                org.springframework.data.domain.PageRequest.of(0, 1));
        if (candidates.isEmpty()) {
            return null;
        }
        SummaryJob job = candidates.get(0);
        job.claim(owner, LocalDateTime.now().plus(properties.lease()));
        return job.getId();
    }

    /**
     * 생성 준비. 작업 소유권 확인 → 세션이 ACTIVE 면 대화를 모아 컨텍스트 반환.
     * 세션이 ACTIVE 가 아니면(이미 종료/없음) 작업을 성공 처리하고 null 반환 — 더 할 일이 없다.
     * 세션 상태는 바꾸지 않는다(차단은 PROCESSING 작업 존재로 도출).
     */
    @Transactional
    public SummaryGenerationContext prepareGeneration(Long jobId, String owner) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            return null;
        }
        AiChatSession session = aiChatSessionRepository.findByIdForUpdate(job.getAiChatSessionId()).orElse(null);
        if (session == null || !session.getStatus().equals(AiChatSession.Status.ACTIVE)) {
            job.markSucceeded();
            return null;
        }
        List<AiChatMessage> messages =
                aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(session.getId());
        return new SummaryGenerationContext(session.getId(), session.getUserBookId(), messages);
    }

    /** 성공 기록 — 감상문 저장 + 세션 영구 잠금 + 작업 성공. 소유권/세션상태 재확인. */
    @Transactional
    public void recordSuccess(Long jobId, String owner, Long userBookId, SummaryDraftResult result) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("감상문 성공 기록 소유권 상실 jobId={}", jobId);
            return;
        }
        AiChatSession session = aiChatSessionRepository.findByIdForUpdate(job.getAiChatSessionId()).orElse(null);
        if (session == null || !session.getStatus().equals(AiChatSession.Status.ACTIVE)) {
            job.markSucceeded(); // 다른 경로가 이미 종료 — 작업만 마감
            return;
        }
        summaryRepository.save(Summary.createCompleted(
                userBookId, session.getId(), result.title(), result.body()));
        session.lock();
        job.markSucceeded();
    }

    /** 실패 기록 — 재시도 가능하고 상한 미만이면 백오프 재시도, 아니면 FAILED. 세션은 건드리지 않는다. */
    @Transactional
    public void recordFailure(
            Long jobId, String owner, boolean retryable,
            String errorCode, String errorMessage, LocalDateTime explicitRetryAt
    ) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("감상문 실패 기록 소유권 상실 jobId={}", jobId);
            return;
        }
        boolean canRetry = retryable && job.getAttemptCount() + 1 < properties.maxAttempts();
        if (canRetry) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = explicitRetryAt != null
                    ? explicitRetryAt
                    : properties.nextAttemptFrom(now, job.getAttemptCount());
            job.scheduleRetry(next, errorCode, errorMessage);
        } else {
            job.markFailed(errorCode, errorMessage);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryJobTxServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/readum/domain/summary/service/SummaryJobTxService.java \
        src/test/java/com/readum/domain/summary/service/SummaryJobTxServiceTest.java
git commit -m "feat(summary): 작업 처리 트랜잭션 단계 SummaryJobTxService(펜싱 포함) 추가"
```

---

## Task 9: SummaryGenerationWorker (비-TX 오케스트레이터)

**Files:**
- Create: `src/main/java/com/readum/domain/summary/service/SummaryGenerationWorker.java`
- Test: `src/test/java/com/readum/domain/summary/service/SummaryGenerationWorkerTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/readum/domain/summary/service/SummaryGenerationWorkerTest.java`:

```java
package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryGenerationWorkerTest {

    @Mock private SummaryJobTxService txService;
    @Mock private AiSummaryClient aiSummaryClient;

    @InjectMocks private SummaryGenerationWorker worker;

    @Test
    void 처리할_작업이_없으면_생성을_호출하지_않는다() {
        given(txService.claimOne(anyString())).willReturn(null);

        worker.drainOnce();

        verify(aiSummaryClient, never()).generate(any());
    }

    @Test
    void 정상_흐름은_준비_생성_성공기록을_순서대로_한다() {
        given(txService.claimOne(anyString())).willReturn(10L, (Long) null);
        given(txService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 7L, List.of()));
        given(aiSummaryClient.generate(any())).willReturn(new SummaryDraftResult("제목", "본문"));

        worker.drainOnce();

        verify(txService).recordSuccess(eq(10L), anyString(), eq(7L), any(SummaryDraftResult.class));
    }

    @Test
    void 준비단계가_null이면_생성을_건너뛴다() {
        given(txService.claimOne(anyString())).willReturn(10L, (Long) null);
        given(txService.prepareGeneration(eq(10L), anyString())).willReturn(null);

        worker.drainOnce();

        verify(aiSummaryClient, never()).generate(any());
        verify(txService, never()).recordSuccess(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void 생성중_TooManyRequests면_재시도가능으로_실패기록한다() {
        given(txService.claimOne(anyString())).willReturn(10L, (Long) null);
        given(txService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 7L, List.of()));
        given(aiSummaryClient.generate(any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST));

        worker.drainOnce();

        verify(txService).recordFailure(eq(10L), anyString(), eq(true), anyString(), anyString(), isNull());
    }

    @Test
    void 생성중_일반예외면_재시도가능으로_실패기록한다() {
        given(txService.claimOne(anyString())).willReturn(10L, (Long) null);
        given(txService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 7L, List.of()));
        given(aiSummaryClient.generate(any())).willThrow(new RuntimeException("boom"));

        worker.drainOnce();

        verify(txService).recordFailure(eq(10L), anyString(), eq(true), anyString(), anyString(), isNull());
    }
}
```

> 참고: `TooManyRequestsException` 생성자 시그니처는 기존 코드 기준이다. 구현 시 실제 시그니처(예: `new TooManyRequestsException(ErrorCode)` 또는 `RateLimitInfo` 포함)를 확인해 테스트와 맞춘다. `RateLimitInfo.retryAfter` 가 있으면 그 값을 `explicitRetryAt` 로 넘기도록 구현(아래 Step 3 주석 참고).

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryGenerationWorkerTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 워커 구현**

`src/main/java/com/readum/domain/summary/service/SummaryGenerationWorker.java`:

```java
package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 한 워커 스레드의 작업 처리 루프. 트랜잭션 없이, 각 트랜잭션 단계(SummaryJobTxService)를
 * 순서대로 호출하고 그 사이(트랜잭션 밖)에서 OpenAI 를 부른다.
 * Plan 1 실패 분류는 단순 2분류: TooManyRequests/일반예외 모두 "재시도 가능" 으로 기록(작업 유실 0).
 * (4xx 비재시도 즉시 실패 + quota breaker 는 Plan 2.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerationWorker {

    private final SummaryJobTxService txService;
    private final AiSummaryClient aiSummaryClient;

    /** 처리할 작업이 없을 때까지 계속 선점·처리한다(드레인). */
    public void drain() {
        while (drainOnce()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** 한 작업만 시도. 처리했으면 true, 큐가 비었으면 false. */
    public boolean drainOnce() {
        String owner = UUID.randomUUID().toString();
        Long jobId = txService.claimOne(owner);
        if (jobId == null) {
            return false;
        }
        runJob(jobId, owner);
        return true;
    }

    private void runJob(Long jobId, String owner) {
        SummaryGenerationContext context = txService.prepareGeneration(jobId, owner);
        if (context == null) {
            return; // 소유권 상실 또는 세션이 ACTIVE 아님 — 더 할 일 없음
        }
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(context.messages());
        } catch (TooManyRequestsException e) {
            // Plan 2: Retry-After(RateLimitInfo) 를 explicitRetryAt 으로 전달 + quota 면 breaker open
            txService.recordFailure(jobId, owner, true,
                    e.getErrorCode().name(), e.getMessage(), null);
            return;
        } catch (Exception e) {
            log.warn("감상문 생성 실패 jobId={} sessionId={}", jobId, context.sessionId(), e);
            txService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        }
        txService.recordSuccess(jobId, owner, context.userBookId(), result);
    }
}
```

> 구현 시 `TooManyRequestsException` 의 실제 접근자(`getErrorCode()` 등)와 `RateLimitInfo` 보유 여부를 확인해 `e.getErrorCode().name()` 부분을 맞춘다. 접근자가 다르면 그에 맞게 수정(테스트도 함께).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryGenerationWorkerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/readum/domain/summary/service/SummaryGenerationWorker.java \
        src/test/java/com/readum/domain/summary/service/SummaryGenerationWorkerTest.java
git commit -m "feat(summary): 생성 오케스트레이터 SummaryGenerationWorker 추가"
```

---

## Task 10: SummaryJobDispatcher (주기적 선점·제출)

**Files:**
- Create: `src/main/java/com/readum/infrastructure/summary/scheduler/SummaryJobDispatcher.java`
- Test: `src/test/java/com/readum/infrastructure/summary/scheduler/SummaryJobDispatcherTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/readum/infrastructure/summary/scheduler/SummaryJobDispatcherTest.java`:

```java
package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.service.SummaryGenerationWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryJobDispatcherTest {

    @Mock private SummaryGenerationWorker worker;
    @Mock private SummaryJobProperties properties;

    @Test
    void dispatch_는_풀크기만큼_드레인_작업을_제출한다() {
        // 같은 스레드에서 즉시 실행하는 Executor 로 검증
        Executor directExecutor = Runnable::run;
        given(properties.poolSize()).willReturn(3);
        SummaryJobDispatcher dispatcher = new SummaryJobDispatcher(worker, directExecutor, properties);

        dispatcher.dispatch();

        verify(worker, times(3)).drain();
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew test --tests "com.readum.infrastructure.summary.scheduler.SummaryJobDispatcherTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 디스패처 구현**

`src/main/java/com/readum/infrastructure/summary/scheduler/SummaryJobDispatcher.java`:

```java
package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.service.SummaryGenerationWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주기적으로 워커 드레인 작업을 풀에 제출한다. 실제 선점은 워커가 SKIP LOCKED 로 하므로
 * 여러 드레인 스레드가 동시에 돌아도 같은 작업을 겹쳐 잡지 않는다.
 * 동시에 도는 드레인 수를 poolSize 로 제한해 OpenAI 동시성을 한도 아래로 묶는다.
 */
@Slf4j
@Component
public class SummaryJobDispatcher {

    private final SummaryGenerationWorker worker;
    private final Executor summaryExecutor;
    private final SummaryJobProperties properties;
    private final AtomicInteger activeDrainers = new AtomicInteger(0);

    public SummaryJobDispatcher(
            SummaryGenerationWorker worker,
            @Qualifier("summaryExecutor") Executor summaryExecutor,
            SummaryJobProperties properties
    ) {
        this.worker = worker;
        this.summaryExecutor = summaryExecutor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${summary-job.dispatch-interval-ms}")
    public void dispatch() {
        while (activeDrainers.get() < properties.poolSize()) {
            activeDrainers.incrementAndGet();
            summaryExecutor.execute(() -> {
                try {
                    worker.drain();
                } catch (Exception e) {
                    log.error("감상문 워커 드레인 중 오류", e);
                } finally {
                    activeDrainers.decrementAndGet();
                }
            });
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.infrastructure.summary.scheduler.SummaryJobDispatcherTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/readum/infrastructure/summary/scheduler/SummaryJobDispatcher.java \
        src/test/java/com/readum/infrastructure/summary/scheduler/SummaryJobDispatcherTest.java
git commit -m "feat(summary): 작업 선점 디스패처 SummaryJobDispatcher 추가"
```

---

## Task 11: SummaryJobReaper (고아 회수) + 회수 리포지토리 메서드

**Files:**
- Modify: `src/main/java/com/readum/domain/summary/service/SummaryJobTxService.java` (회수 메서드 추가)
- Create: `src/main/java/com/readum/infrastructure/summary/scheduler/SummaryJobReaper.java`
- Test: `src/test/java/com/readum/domain/summary/service/SummaryJobTxServiceReapTest.java`

- [ ] **Step 1: 실패 테스트 작성 (회수 트랜잭션)**

`src/test/java/com/readum/domain/summary/service/SummaryJobTxServiceReapTest.java`:

```java
package com.readum.domain.summary.service;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import com.readum.model.summary.repository.SummaryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SummaryJobTxServiceReapTest {

    @Mock private SummaryJobRepository summaryJobRepository;
    @Mock private com.readum.model.aiChat.repository.AiChatSessionRepository aiChatSessionRepository;
    @Mock private com.readum.model.aiChat.repository.AiChatMessageRepository aiChatMessageRepository;
    @Mock private com.readum.model.summary.repository.SummaryRepository summaryRepository;
    @Mock private com.readum.domain.summary.config.SummaryJobProperties properties;

    @Test
    void reclaimOrphans_는_고아작업을_PENDING으로_되돌린다() {
        SummaryJobTxService service = new SummaryJobTxService(
                summaryJobRepository, aiChatSessionRepository, aiChatMessageRepository,
                summaryRepository, properties);
        SummaryJob orphan = SummaryJobFixture.persistedProcessing(
                10L, 1L, "dead", LocalDateTime.now().minusMinutes(1));
        given(summaryJobRepository.findOrphaned(any(), any())).willReturn(List.of(orphan));

        int reclaimed = service.reclaimOrphans(100);

        assertThat(reclaimed).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(orphan.getLockOwner()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryJobTxServiceReapTest"`
Expected: 컴파일 실패 (`reclaimOrphans` 없음)

- [ ] **Step 3: TxService 에 회수 메서드 추가**

`SummaryJobTxService.java` 에 추가:

```java
    /**
     * lease 만료된 고아 작업을 PENDING 으로 되돌린다(즉시 재선점 가능). 세션은 건드리지 않는다 —
     * 차단은 PROCESSING 의 유효 lease 가 사라지면 자동 해제되기 때문.
     * @return 회수한 작업 수
     */
    @Transactional
    public int reclaimOrphans(int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        List<SummaryJob> orphans = summaryJobRepository.findOrphaned(
                now, org.springframework.data.domain.PageRequest.of(0, batchSize));
        orphans.forEach(job -> job.releaseAfterOrphan(now));
        return orphans.size();
    }
```

- [ ] **Step 4: Reaper 컴포넌트 작성**

`src/main/java/com/readum/infrastructure/summary/scheduler/SummaryJobReaper.java`:

```java
package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.service.SummaryJobTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멈춘(고아) 작업 회수기. lease 만료된 PROCESSING 작업을 PENDING 으로 되돌려 다시 처리되게 한다.
 * 서버 재시작/워커 장애 시 작업이 영영 PROCESSING 에 갇히는 것을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryJobReaper {

    private static final int BATCH_SIZE = 100;

    private final SummaryJobTxService summaryJobTxService;

    @Scheduled(fixedDelayString = "${summary-job.reaper-interval-ms}")
    public void reclaim() {
        int reclaimed = summaryJobTxService.reclaimOrphans(BATCH_SIZE);
        if (reclaimed > 0) {
            log.info("멈춘 감상문 작업 회수 {}건", reclaimed);
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.domain.summary.service.SummaryJobTxServiceReapTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/readum/domain/summary/service/SummaryJobTxService.java \
        src/main/java/com/readum/infrastructure/summary/scheduler/SummaryJobReaper.java \
        src/test/java/com/readum/domain/summary/service/SummaryJobTxServiceReapTest.java
git commit -m "feat(summary): 고아 작업 회수기 SummaryJobReaper 추가"
```

---

## Task 12: 생성 경로를 큐로 전환 — SummaryScheduler + SummaryDraftService

이 작업으로 "직접 생성" 호출이 사라진다. `SummaryScheduler` 는 적재만, `SummaryDraftService.execute` 는 적재만 한다. `TransactionTemplate`/`@Async`/`generateAndRecord`/`executeForScheduler` 를 제거한다.

**Files:**
- Modify: `src/main/java/com/readum/infrastructure/aiChat/scheduler/SummaryScheduler.java`
- Modify: `src/main/java/com/readum/domain/aiChat/service/SummaryDraftService.java`
- Test: `src/test/java/com/readum/domain/aiChat/service/SummaryDraftServiceTest.java` (재작성)

- [ ] **Step 1: SummaryDraftService 단위 테스트 재작성**

`SummaryDraftServiceTest.java` 전체를 교체:

```java
package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryDraftServiceTest {

    private static final String USER_SESSION_ID = "user-session";
    private static final Long USER_ID = 10L;
    private static final Long SESSION_ID = 1L;
    private static final Long USER_BOOK_ID = 5L;

    @Mock private UserRepository userRepository;
    @Mock private AiChatSessionRepository aiChatSessionRepository;
    @Mock private UserBookRepository userBookRepository;
    @Mock private SummaryDraftPolicy summaryDraftPolicy;
    @Mock private EnqueueSummaryJobService enqueueSummaryJobService;

    @InjectMocks private SummaryDraftService summaryDraftService;

    private User user() {
        return UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
    }

    @Test
    void 자격을_통과하면_작업을_적재한다() {
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 2, 600, "제목");
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(user()));
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID))
                .willReturn(Optional.of(any(UserBook.class) == null ? null : null)); // placeholder 회피: 아래 주석 참조

        // 위 한 줄은 컴파일되지 않으므로, UserBook 픽스처로 교체한다:
        // given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID))
        //         .willReturn(Optional.of(UserBookFixture.persistedUserBook(USER_BOOK_ID, USER_ID, ...)));

        summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

        verify(enqueueSummaryJobService).execute(SESSION_ID);
    }

    @Test
    void 사용자_세션이_유효하지_않으면_401() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .isInstanceOf(UnauthorizedException.class);
        verify(enqueueSummaryJobService, never()).execute(any());
    }
}
```

> **주의:** 위 `given(userBookRepository...)` 줄은 의도적으로 잘못된 placeholder 다. 구현자는 실제 `UserBookFixture` 의 팩토리(예: `UserBookFixture.persistedUserBook(...)`)를 확인해 `Optional.of(...)` 로 교체하라. `UserFixture.persistedUser(id, sessionId)` 시그니처도 실제 픽스처에 맞춰 확인한다(다르면 맞춘다). 소유권 검증을 통과시키는 게 목적이다.

- [ ] **Step 2: 테스트 실패 확인 (재작성된 시그니처로)**

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.SummaryDraftServiceTest"`
Expected: 컴파일/실행 실패 (아직 SummaryDraftService 가 옛 구조)

- [ ] **Step 3: SummaryDraftService 재작성 (적재 전용)**

`SummaryDraftService.java` 전체 교체:

```java
package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수동(데모) 감상문 생성 요청 진입점. 직접 생성하지 않고 작업을 적재한다.
 * 실제 생성은 작업 큐 워커(SummaryGenerationWorker)가 처리한다.
 * 자격(ACTIVE + 누적 토큰 ≥ 임계값, 종료 세션 제외)은 SummaryDraftPolicy 가 검증한다.
 * 중복 적재는 EnqueueSummaryJobService 의 멱등성(active_session_id unique)이 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryDraftService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final UserBookRepository userBookRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final EnqueueSummaryJobService enqueueSummaryJobService;

    @Transactional
    public void execute(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        AiChatSession session = aiChatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        userBookRepository.findByIdAndUserId(session.getUserBookId(), user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        summaryDraftPolicy.assertEligible(session);

        enqueueSummaryJobService.execute(sessionId);
    }
}
```

- [ ] **Step 4: SummaryScheduler 재작성 (적재 전용)**

`SummaryScheduler.java` 전체 교체:

```java
package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 매일 오전 6시, 최근 대화한 세션의 감상문 생성 "작업을 적재" 한다(직접 생성하지 않음).
 * 대상 = ACTIVE + 누적 토큰 ≥ 임계값 + 마지막 채팅이 24시간 이내. (종료된 세션은 ACTIVE 가 아니라 자동 제외)
 * 실제 생성은 작업 큐 워커가 OpenAI 한도에 맞춰 분산 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryScheduler {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final EnqueueSummaryJobService enqueueSummaryJobService;

    @Scheduled(cron = "0 0 6 * * *")
    public void enqueueDailySummaryJobs() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Long> targetSessionIds = aiChatSessionRepository.findAutoSummaryTargetSessionIds(
                AiChatSession.Status.ACTIVE,
                SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS,
                AiChatMessage.Status.COMPLETED,
                since);
        log.info("감상문 자동 생성 작업 적재 시작 대상 {}건", targetSessionIds.size());

        targetSessionIds.forEach(enqueueSummaryJobService::execute);

        log.info("감상문 자동 생성 작업 적재 완료");
    }
}
```

> `SummarySchedulerConfig`(summaryExecutor 빈)는 그대로 둔다 — 디스패처가 재사용한다.

- [ ] **Step 5: 테스트 통과 확인 + 전체 컴파일**

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.SummaryDraftServiceTest"`
Expected: PASS
Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL (이제 `unlock()` 호출자가 사라짐)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/readum/infrastructure/aiChat/scheduler/SummaryScheduler.java \
        src/main/java/com/readum/domain/aiChat/service/SummaryDraftService.java \
        src/test/java/com/readum/domain/aiChat/service/SummaryDraftServiceTest.java
git commit -m "refactor(summary): 직접 생성 경로를 작업 큐 적재로 전환(TransactionTemplate/@Async 제거)"
```

---

## Task 13: AiChatSession.unlock() 제거 + 픽스처 정리

**Files:**
- Modify: `src/main/java/com/readum/model/aiChat/entity/AiChatSession.java`
- Modify: `src/test/java/com/readum/model/aiChat/entity/AiChatSessionFixture.java`

- [ ] **Step 1: unlock() 사용처 확인**

Run: `grep -rn "\.unlock()" src/main src/test`
Expected: 출력 없음 (Task 12 이후 호출자 없음). 있으면 먼저 제거.

- [ ] **Step 2: unlock() 삭제**

`AiChatSession.java` 에서 `unlock()` 메서드 블록을 통째로 삭제한다(아래 블록 제거):

```java
    /**
     * 감상문 생성이 끝나면(성공/실패 무관) 세션을 다시 활성화해 대화를 이어갈 수 있게 한다.
     */
    public void unlock() {
        this.status = Status.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }
```

- [ ] **Step 3: 픽스처 명명 정리 (LOCKED = 종료)**

`AiChatSessionFixture.java` 의 `persistedLockedSession` Javadoc·이름을 종료 의미로 바꾼다. 메서드 이름을 `persistedSummarizedSession` 으로 변경하고 Javadoc 교체:

```java
    /**
     * 저장되어 id 가 부여된, 감상문이 완성되어 종료(LOCKED)된 세션.
     */
    public static AiChatSession persistedSummarizedSession(
            Long id,
            Long userBookId,
            int userMessageCount,
            int accumulatedTokens,
            String title
    ) {
        return persisted(id, userBookId, AiChatSession.Status.LOCKED,
                userMessageCount, accumulatedTokens, title);
    }
```

- [ ] **Step 4: 이름 변경된 픽스처 사용처 수정**

Run: `grep -rn "persistedLockedSession" src/test`
사용처를 모두 `persistedSummarizedSession` 으로 바꾼다.

- [ ] **Step 5: 컴파일 + 관련 테스트**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/readum/model/aiChat/entity/AiChatSession.java \
        src/test/java/com/readum/model/aiChat/entity/AiChatSessionFixture.java
git commit -m "refactor(aiChat): unlock() 제거 + 픽스처를 종료(LOCKED) 의미로 정리"
```

---

## Task 14: 자격 정책 + 에러 코드 (종료 세션 분기)

**Files:**
- Modify: `src/main/java/com/readum/domain/aiChat/exception/AiChatErrorCode.java`
- Modify: `src/main/java/com/readum/domain/aiChat/dto/SummaryDraftEligibility.java`
- Modify: `src/main/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicy.java`
- Test: `src/test/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicyTest.java` (없으면 생성)

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicyTest.java`:

```java
package com.readum.domain.aiChat.service.policy;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryDraftPolicyTest {

    private final SummaryDraftPolicy policy = new SummaryDraftPolicy();

    @Test
    void 종료된_세션은_ALREADY_SUMMARIZED로_막는다() {
        AiChatSession session = AiChatSessionFixture.persistedSummarizedSession(1L, 5L, 2, 600, "제목");

        assertThatThrownBy(() -> policy.assertEligible(session))
                .isInstanceOf(ConflictException.class)
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED);
    }

    @Test
    void 누적토큰이_부족하면_CHAT_VOLUME_NOT_ENOUGH() {
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1L, 5L, 1, 100, "제목");

        assertThatThrownBy(() -> policy.assertEligible(session))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void ACTIVE에_충분한_토큰이면_통과한다() {
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1L, 5L, 2, 600, "제목");

        assertThatCode(() -> policy.assertEligible(session)).doesNotThrowAnyException();
    }
}
```

> `ConflictException.getErrorCode()` 접근자 존재를 확인한다. 없으면 기존 다른 예외 테스트(`SummaryEditServiceTest`)의 검증 방식과 동일하게 맞춘다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.policy.SummaryDraftPolicyTest"`
Expected: FAIL (LOCKED→ALREADY_SUMMARIZED 분기 없음)

- [ ] **Step 3: 에러 코드 추가**

`AiChatErrorCode.java` 의 enum 에 추가(`SUMMARY_IN_PROGRESS` 다음):

```java
    SESSION_ALREADY_SUMMARIZED("이미 감상문이 생성되어 종료된 세션입니다."),
```

- [ ] **Step 4: IneligibleReason 추가**

`SummaryDraftEligibility.java` 의 enum 에 추가:

```java
        ALREADY_SUMMARIZED(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED),
```

- [ ] **Step 5: 정책 분기 변경**

`SummaryDraftPolicy.java` 의 `evaluate` 와 `assertEligible` 의 switch 를 교체:

```java
    public SummaryDraftEligibility evaluate(AiChatSession session) {
        return switch (session.getStatus()) {
            case LOCKED -> SummaryDraftEligibility.fail(IneligibleReason.ALREADY_SUMMARIZED);
            case ACTIVE -> session.getAccumulatedTokens() < MIN_ACCUMULATED_TOKENS
                    ? SummaryDraftEligibility.fail(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH)
                    : SummaryDraftEligibility.pass();
        };
    }

    public void assertEligible(AiChatSession session) {
        SummaryDraftEligibility eligibility = evaluate(session);
        if (eligibility.eligible()) {
            return;
        }
        throw switch (eligibility.reason()) {
            case ALREADY_SUMMARIZED -> new ConflictException(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED);
            case SUMMARY_IN_PROGRESS -> new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
            case CHAT_VOLUME_NOT_ENOUGH -> new UnprocessableEntityException(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
        };
    }
```

> `LOCKED` 가 종료를 뜻하므로 "생성 중(SUMMARY_IN_PROGRESS)" 분기는 더 이상 세션 상태로 판정하지 않는다. "생성 중" 차단은 메시지 전송 가드(Task 15)가 활성 PROCESSING 작업으로 처리한다. `SUMMARY_IN_PROGRESS` enum 상수는 다른 곳(조회)에서 쓰이므로 남겨둔다.

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.policy.SummaryDraftPolicyTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/readum/domain/aiChat/exception/AiChatErrorCode.java \
        src/main/java/com/readum/domain/aiChat/dto/SummaryDraftEligibility.java \
        src/main/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicy.java \
        src/test/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicyTest.java
git commit -m "feat(aiChat): 종료 세션 자격 분기(ALREADY_SUMMARIZED) 추가"
```

---

## Task 15: 메시지 전송/조회 가드 — "생성 중" 을 PROCESSING 작업으로 판정

**Files:**
- Modify: `src/main/java/com/readum/domain/aiChat/service/AiChatMessagePersistService.java`
- Modify: `src/main/java/com/readum/domain/summary/service/SummarySearchService.java`
- Test: 각 서비스의 기존 테스트 보강

- [ ] **Step 1: AiChatMessagePersistService 가드 변경**

`AiChatMessagePersistService` 에 `SummaryJobRepository` 의존성 추가하고 `loadHistory` 의 가드를 교체.

생성자 주입 필드 추가:

```java
    private final com.readum.model.summary.repository.SummaryJobRepository summaryJobRepository;
```

`loadHistory` 의 차단 블록 교체:

```java
        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
        if (session.isLocked()) {
            // 감상문이 완성되어 종료된 세션 — 영구히 대화 불가
            throw new BadRequestException(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED);
        }
        if (summaryJobRepository.existsActiveProcessingJob(sessionId, java.time.LocalDateTime.now())) {
            // 지금 생성 중 — 일시적으로 전송 불가
            throw new BadRequestException(AiChatErrorCode.SESSION_LOCKED);
        }
```

- [ ] **Step 2: SummarySearchService 가드 변경 + 단건 조회 사용**

`SummarySearchService.findBySessionId` 교체(생성자에 `SummaryJobRepository` 추가):

```java
    @Transactional(readOnly = true)
    public SummaryResult findBySessionId(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        // "생성 중" 은 유효 PROCESSING 작업으로 판정(폴링 계약: 409 유지)
        if (summaryJobRepository.existsActiveProcessingJob(sessionId, java.time.LocalDateTime.now())) {
            throw new ConflictException(SummaryErrorCode.SUMMARY_IN_PROGRESS);
        }

        Summary summary = summaryRepository.findByAiChatSessionId(sessionId)
                .orElseThrow(() -> new NotFoundException(SummaryErrorCode.SUMMARY_NOT_YET_CREATED));

        return SummaryResult.from(summary);
    }
```

생성자 필드 추가:

```java
    private final com.readum.model.summary.repository.SummaryJobRepository summaryJobRepository;
```

> 이제 `SummaryRepository.findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc` 가 미사용이면 제거한다(사용처 grep 후).

- [ ] **Step 3: 컴파일 + 관련 테스트 보강**

기존 `AiChatMessagePersistServiceTest` 의 `loadHistory` 차단 테스트가 있으면 `isLocked()` → 종료 세션은 `SESSION_ALREADY_SUMMARIZED`, 생성 중(PROCESSING 작업 mock)은 `SESSION_LOCKED` 로 갈라 검증하도록 수정. `summaryJobRepository.existsActiveProcessingJob(...)` mock 추가.

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.AiChatMessagePersistServiceTest"`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/readum/domain/aiChat/service/AiChatMessagePersistService.java \
        src/main/java/com/readum/domain/summary/service/SummarySearchService.java \
        src/test/java/com/readum/domain/aiChat/service/AiChatMessagePersistServiceTest.java
git commit -m "feat(aiChat): 생성 중 차단을 PROCESSING 작업 기준으로 도출(종료 vs 생성 중 분기)"
```

---

## Task 16: 세션 목록 표시 상태 CASE 변경

**Files:**
- Modify: `src/main/java/com/readum/model/aiChat/repository/AiChatSessionRepository.java`
- Test: `src/test/java/com/readum/model/aiChat/repository/AiChatSessionRepositoryTest.java` (보강)

- [ ] **Step 1: 실패 테스트 추가**

`AiChatSessionRepositoryTest.java` 에 케이스 추가(기존 셋업 패턴 활용): "LOCKED 세션 → SUMMARIZED", "활성 PROCESSING 작업이 있는 ACTIVE 세션 → SUMMARIZING", "그 외 ACTIVE → ACTIVE" 를 `findSessionsByUserBookIdAndOwner` 결과의 status 로 단언. (SummaryJob 행은 `SummaryJobFixture.persistedProcessing` 으로 저장, lockedUntil 을 미래로.)

```java
    @Test
    void 표시상태_LOCKED는_SUMMARIZED_생성중작업있으면_SUMMARIZING() {
        // given: userBook + Book + owner 세팅(기존 테스트 헬퍼 재사용)
        //   - 세션A: LOCKED
        //   - 세션B: ACTIVE + 유효 PROCESSING summary_job
        //   - 세션C: ACTIVE + 작업 없음
        // when: findSessionsByUserBookIdAndOwner(...)
        // then: A → "SUMMARIZED", B → "SUMMARIZING", C → "ACTIVE"
        // (구체 세팅은 이 파일의 기존 테스트들과 동일한 헬퍼/시퀀스로 작성)
    }
```

> 이 테스트는 기존 파일의 셋업 헬퍼(book/userBook/세션 저장)를 그대로 따른다. 구현자는 같은 파일의 다른 테스트를 참고해 given 블록을 채운다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.readum.model.aiChat.repository.AiChatSessionRepositoryTest"`
Expected: FAIL

- [ ] **Step 3: 목록 JPQL CASE 교체**

`findSessionsByUserBookIdAndOwnerInternal` 의 status CASE 식을 교체한다. 기존 `lockedStatus`(LOCKED→SUMMARIZING) 대신, `LOCKED → 'SUMMARIZED'` 이고 유효 PROCESSING 작업이 있으면 `'SUMMARIZING'`:

default 메서드와 @Query 를 아래로 교체:

```java
    default Slice<AiChatSessionListProjection> findSessionsByUserBookIdAndOwner(
            Long userBookId, Long userId, Pageable pageable
    ) {
        return findSessionsByUserBookIdAndOwnerInternal(
                userBookId,
                userId,
                AiChatSession.Status.LOCKED,
                AiChatMessage.Status.COMPLETED,
                java.time.LocalDateTime.now(),
                pageable
        );
    }

    @Query("""
            select new com.readum.model.aiChat.repository.projection.AiChatSessionListProjection(
                       aiChatSession.id
                     , aiChatSession.title
                     , case
                           when aiChatSession.status = :lockedStatus then 'SUMMARIZED'
                           when exists (
                                    select 1
                                      from SummaryJob summaryJob
                                     where summaryJob.aiChatSessionId = aiChatSession.id
                                       and summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                                       and summaryJob.lockedUntil > :now
                                ) then 'SUMMARIZING'
                           else 'ACTIVE'
                       end
                     , coalesce(
                           (select max(aiChatMessage.createdAt)
                              from AiChatMessage aiChatMessage
                             where aiChatMessage.sessionId = aiChatSession.id
                               and aiChatMessage.status = :messageCompletedStatus),
                           aiChatSession.createdAt
                       )
                   )
              from AiChatSession aiChatSession
             where aiChatSession.userBookId = :userBookId
               and exists (
                     select 1
                       from UserBook userBook
                      where userBook.id = aiChatSession.userBookId
                        and userBook.userId = :userId
                   )
             order by
                coalesce(
                    (select max(aiChatMessage.createdAt)
                       from AiChatMessage aiChatMessage
                      where aiChatMessage.sessionId = aiChatSession.id
                        and aiChatMessage.status = :messageCompletedStatus),
                    aiChatSession.createdAt
                ) desc,
                aiChatSession.id desc
            """)
    Slice<AiChatSessionListProjection> findSessionsByUserBookIdAndOwnerInternal(
            @Param("userBookId") Long userBookId,
            @Param("userId") Long userId,
            @Param("lockedStatus") AiChatSession.Status lockedStatus,
            @Param("messageCompletedStatus") AiChatMessage.Status messageCompletedStatus,
            @Param("now") java.time.LocalDateTime now,
            Pageable pageable
    );
```

> 기존 주석(상태 도출 설명)도 새 규칙(LOCKED=종료=SUMMARIZED, PROCESSING 작업=SUMMARIZING)에 맞게 갱신한다. `Summary` 존재로 SUMMARIZED 를 판정하던 옛 서브쿼리는 제거된다(종료 세션이 곧 SUMMARIZED).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.model.aiChat.repository.AiChatSessionRepositoryTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/readum/model/aiChat/repository/AiChatSessionRepository.java \
        src/test/java/com/readum/model/aiChat/repository/AiChatSessionRepositoryTest.java
git commit -m "feat(aiChat): 목록 표시 상태를 종료(SUMMARIZED)/생성중(PROCESSING 작업) 기준으로 변경"
```

---

## Task 17: 컨트롤러 Swagger 문구 갱신

**Files:**
- Modify: `src/main/java/com/readum/presentation/controller/aiChat/AiChatController.java`

- [ ] **Step 1: 종료 모델에 맞게 설명 수정**

`AiChatController.java` 의 세션 목록·메시지 전송 관련 `@Operation`/`description` 에서 "SUMMARIZING 을 제외한 모든 세션은 대화를 이어갈 수 있다" 같은 #69 전제 문구를, 종료 모델로 교체한다. 예:

```java
                    "각 세션은 ACTIVE / SUMMARIZING / SUMMARIZED 상태로 구분된다 — " +
                    "SUMMARIZING 은 감상문 생성 중(메시지 전송 불가), SUMMARIZED 는 감상문이 완성되어 " +
                    "종료된 세션(영구히 대화 불가)을 의미한다. ACTIVE 세션만 대화를 이어갈 수 있다. " +
```

메시지 전송 엔드포인트 설명에도 "감상문이 완성된(SUMMARIZED) 세션은 종료되어 전송할 수 없다" 를 반영.

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/readum/presentation/controller/aiChat/AiChatController.java
git commit -m "docs(aiChat): Swagger 설명을 종료 모델로 갱신"
```

---

## Task 18: 전체 검증

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 미사용 코드 점검**

Run: `grep -rn "executeForScheduler\|generateAsync\|findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc\|persistedLockedSession\|\.unlock()" src/main src/test`
Expected: 출력 없음(전부 정리됨). 남아 있으면 제거.

- [ ] **Step 3: 최종 커밋(있으면)**

```bash
git add -A
git commit -m "chore(summary): Plan 1 마무리 — 미사용 코드 정리"
```

---

## Self-Review (작성자 점검 결과)

**Spec 커버리지:** 2절 종료 모델(Task 6·13), 2.3 PROCESSING 도출 차단(Task 15·16), 2.4 1:1 unique(Task 7), 4절 summary_job(Task 1·2)·lease/펜싱(Task 1·8), 5절 두 중복 방지(Task 1 unique·2 SKIP LOCKED), 6절 적재기(Task 12), 7절 디스패처/워커/TX(Task 8·9·10), 9절 재시도(Task 8, 단 4분류→2분류로 축소=범위 메모 명시), 10절 회수기(Task 11), 13절 TransactionTemplate 제거(SummaryDraftService=Task 12 / AiChatSessionTitleService=Plan 3), 14절 설정(Task 3). → 11·12절(breaker/pacing)은 Plan 2 로 분리(설계 동일).

**Placeholder:** Task 12 Step 1 의 `given(userBookRepository...)` 한 줄은 **의도적 표시** — 구현자가 실제 `UserBookFixture` 시그니처로 교체하도록 주석으로 명시함. Task 16 Step 1 given 블록도 "기존 파일 헬퍼 재사용" 으로 위임(이 파일의 셋업이 길어 복제 대신 참조 지시).

**타입 일관성:** `claimOne(owner)→Long`, `prepareGeneration(jobId,owner)→SummaryGenerationContext|null`, `recordSuccess(jobId,owner,userBookId,result)`, `recordFailure(jobId,owner,retryable,code,msg,explicitRetryAt)`, `reclaimOrphans(batchSize)→int`, `existsActiveProcessingJob(sessionId,now)`, `findByAiChatSessionId(sessionId)` — 정의(Task 8·11·7)와 사용처(Task 9·15·16) 시그니처 일치 확인.

**구현 중 확인 필요(코드에 주석으로 표시):** `TooManyRequestsException`/`ConflictException` 접근자, `UserFixture`/`UserBookFixture` 시그니처, SKIP LOCKED 힌트의 MySQL 실동작(H2 테스트는 선별/전이만 검증).
