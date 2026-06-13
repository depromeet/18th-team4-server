# dev 통합 + 감상문 모델 재작업 구현 계획

> **For agentic workers:** 이 계획은 `origin/dev` 병합 후 충돌·중복 파일을 통합 모델로 재작성하는 작업이다. 변경이 서로 강하게 얽혀 있어(모델 변경이 여러 파일로 일관되게 퍼짐) **인라인 실행(executing-plans)** 이 적합하다. 단계는 체크박스(`- [ ]`)로 추적한다.

**Goal:** `feature/69-resume-summarized-session` 브랜치를 현재 `origin/dev`(스케줄러·편집·기록 포함)와 병합하면서, 감상문을 LOCKED/write-once/실패-행-없음 모델로 통일하고, 책별 대화 세션 목록 기능을 추가한다.

**Architecture:** 세션 `{ACTIVE, LOCKED}` · Summary 는 성공 기록만 write-once(status 필드 없음) · 실패 시 행 미생성(로그만) · 자동 생성은 전체 대화로 재요약 + 채팅활동 기준 대상 선정 · 책별 세션 목록 조회 신규.

**Tech Stack:** Spring Boot 4.0.5, Java 25, JPA/Hibernate 6+, H2(test, create-drop), JUnit5 + Mockito + AssertJ.

**선행 설계:** [docs/superpowers/specs/2026-06-13-reconcile-summary-model-with-dev.md](../specs/2026-06-13-reconcile-summary-model-with-dev.md)

---

## 실행 메모 (전체 공통)

- **병합 충돌 해소는 git 마커를 손으로 푸는 게 아니라, 각 파일을 아래 목표 상태로 전면 재작성**하는 것이다. 자동 병합된 파일도 의미상 깨졌을 수 있으니(예: ErrorCode 가 양쪽 값을 다 가짐) 목표 상태와 대조해 정리한다.
- 병합 직후 트리는 컴파일되지 않는다. **모든 재작성을 끝낸 뒤** `./gradlew compileJava`로 한 번에 맞춘다.
- 커밋 단위: (1) 병합+모델 통일을 **하나의 merge commit** 으로, (2) 책별 세션 목록 신규 기능을 **별도 commit** 으로.
- quote: 엔티티 `quote` 컬럼은 dev 에서 이미 DTO/프롬프트에서 빠진 잔존 컬럼이다. 컬럼은 nullable 로 두고 factory 에서 채우지 않는다(별도 정리 안 함, 범위 최소화).

---

## Task 1: dev 병합 시작

**파일:** 없음(git 작업)

- [ ] **Step 1: 최신 dev 확인 후 병합 시작**

```bash
git fetch origin
git merge origin/dev --no-edit
# 충돌 9개 + 자동병합 파일들. 다음 Task 들에서 목표 상태로 재작성한다.
git status
```

충돌 예상: `Summary`, `SummaryRepository`, `SummaryDraftService`, `SummarySearchService`, 그리고 테스트 4개(`SummaryDraftServiceTest`, `AiChatSessionRepositoryTest`, `SummaryFixture`, `SummaryRepositoryTest`, `AiChatControllerTest`). 자동병합됐지만 검토 필요: `AiChatErrorCode`, `SummaryDraftPolicy`, `AiChatSessionRepository`, `AiChatController`, `SummaryDraftSearchServiceTest`.

커밋하지 않고 Task 2~10 진행.

---

## Task 2: `AiChatSession` 엔티티 — `{ACTIVE, LOCKED}`

**파일:** Modify `src/main/java/com/readum/model/aiChat/entity/AiChatSession.java`

- [ ] **Step 1: Status enum 과 메서드를 LOCKED 모델로 교체**

`enum Status { ACTIVE, CLOSED }` → `enum Status { ACTIVE, LOCKED }`. 메서드 교체:

```java
public void lock() {
    this.status = Status.LOCKED;
    this.updatedAt = LocalDateTime.now();
}

public void unlock() {
    this.status = Status.ACTIVE;
    this.updatedAt = LocalDateTime.now();
}

public boolean isLocked() {
    return this.status == Status.LOCKED;
}
```

`close()`/`isClosed()` 삭제. 나머지(create, appendUserMessage, updateTitle, addAssistantTokens 등)는 dev 그대로 유지.

---

## Task 3: `Summary` 엔티티 — write-once, status·유니크·retryCount·summaryDate 제거

**파일:** Modify `src/main/java/com/readum/model/summary/entity/Summary.java`

- [ ] **Step 1: 엔티티를 성공 기록 전용 write-once 로 재작성**

목표 상태(전체):

```java
package com.readum.model.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "summary",
        indexes = {
                @Index(name = "idx_summary_user_book", columnList = "user_book_id"),
                @Index(name = "idx_summary_session", columnList = "ai_chat_session_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class Summary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_book_id", nullable = false)
    private Long userBookId;

    @Column(name = "ai_chat_session_id", nullable = false)
    private Long aiChatSessionId;

    @Column(name = "quote", columnDefinition = "TEXT")
    private String quote;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 생성에 성공한 감상문을 새 행으로 기록한다. (write-once — 실패 시엔 행을 만들지 않는다) */
    public static Summary createCompleted(Long userBookId, Long aiChatSessionId, String title, String body) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(null, userBookId, aiChatSessionId, null, title, body, now, now);
    }

    /** 사용자가 자기 감상문 본문/제목을 직접 다듬는 편집(생성과 별개 행위). 제자리 수정. */
    public void edit(String title, String body) {
        this.title = title;
        this.body = body;
        this.updatedAt = LocalDateTime.now();
    }
}
```

제거: `Status` enum, `status` 컬럼, `summaryDate`, `retryCount`, `@UniqueConstraint`, `createInProgress()`/`complete()`/`fail()`/`resetToInProgress()`/`isCompleted()`.
생성자 인자 순서(PACKAGE all-args, 픽스처가 호출): `(id, userBookId, aiChatSessionId, quote, title, body, createdAt, updatedAt)`.

---

## Task 4: `SummaryRepository` — 최신 행 조회·history 최신 1건·cascade

**파일:** Modify `src/main/java/com/readum/model/summary/repository/SummaryRepository.java`

- [ ] **Step 1: 쿼리 정리**

목표:

```java
public interface SummaryRepository extends JpaRepository<Summary, Long> {

    /** 등록 도서 삭제 cascade — 그 도서의 감상 기록 일괄 삭제. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Summary summary where summary.userBookId = :userBookId")
    int deleteAllByUserBookId(@Param("userBookId") Long userBookId);

    /** 세션의 가장 최근 감상문(= 현재 감상문). */
    Optional<Summary> findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(Long aiChatSessionId);

    /** 여러 세션의 최신 감상문을 한 번에 — 책별 세션 목록·표시상태 합성용.
     *  세션별 max(id) 행만 추린다(같은 createdAt 동시 생성 대비 id tiebreak). */
    @Query("""
            select summary
              from Summary summary
             where summary.aiChatSessionId in :sessionIds
               and summary.id = (
                     select max(latest.id)
                       from Summary latest
                      where latest.aiChatSessionId = summary.aiChatSessionId
                   )
            """)
    List<Summary> findLatestByAiChatSessionIdIn(@Param("sessionIds") Collection<Long> sessionIds);

    /** 사용자 본인의 감상 기록 목록 — 세션(=책)당 최신 1건만, 최신순 Slice.
     *  Summary/AiChatSession/UserBook/Book 사이 연관관계가 없어 on 절로 직접 join(Hibernate 6+). */
    @Query("""
            select new com.readum.model.summary.repository.projection.SummaryHistoryProjection(
                       book.title
                     , summary.body
                     , summary.createdAt
                   )
              from Summary summary
              join AiChatSession aiChatSession
                on aiChatSession.id = summary.aiChatSessionId
              join UserBook userBook
                on userBook.id = summary.userBookId
              join Book book
                on book.id = userBook.bookId
             where userBook.userId = :userId
               and summary.id = (
                     select max(latest.id)
                       from Summary latest
                      where latest.aiChatSessionId = summary.aiChatSessionId
                   )
             order by summary.createdAt desc
                    , summary.id desc
            """)
    Slice<SummaryHistoryProjection> findLatestHistoryByUserId(@Param("userId") Long userId, Pageable pageable);
}
```

제거: `findByAiChatSessionIdAndSummaryDate`, `findFirstByAiChatSessionIdOrderByCreatedAtDesc`(→ `...DescIdDesc` 로 교체), `findByStatusAndRetryCountLessThan`, 기존 `findCompletedHistoryByUserId`/`...Internal`(status·CLOSED 필터 제거판으로 교체).
import 정리: `LocalDate`, `Summary.Status`, `AiChatSession.Status` 더 이상 불필요. `Collection` 추가.

---

## Task 5: `SummaryHistoryProjection` 확인

**파일:** Read `src/main/java/com/readum/model/summary/repository/projection/SummaryHistoryProjection.java`

- [ ] **Step 1:** 생성자 시그니처가 `(String bookTitle, String body, LocalDateTime createdAt)` 인지 확인. Task 4 쿼리의 `new ...Projection(book.title, summary.body, summary.createdAt)` 와 일치해야 함. 불일치 시 쿼리를 프로젝션에 맞춘다(프로젝션은 그대로 둠).

---

## Task 6: `AiChatSessionRepository` — 표시상태 CASE 재작성 + 스케줄러 대상 쿼리

**파일:** Modify `src/main/java/com/readum/model/aiChat/repository/AiChatSessionRepository.java`

- [ ] **Step 1: 표시상태 CASE 를 `{ACTIVE, SUMMARIZING, SUMMARIZED}` 로 재작성**

`findSessionsByUserBookIdAndOwner` default + internal `@Query` 를 교체. CASE 규칙: 세션 LOCKED → `'SUMMARIZING'`, 최신 summary 행 존재 → `'SUMMARIZED'`, 그 외 → `'ACTIVE'`. dev 가 참조하던 `Summary.IN_PROGRESS`/`Summary.FAILED`/세션 `CLOSED` 파라미터 제거.

```java
default Slice<AiChatSessionListProjection> findSessionsByUserBookIdAndOwner(
        Long userBookId, Long userId, Pageable pageable
) {
    return findSessionsByUserBookIdAndOwnerInternal(
            userBookId, userId,
            AiChatSession.Status.LOCKED,
            AiChatMessage.Status.COMPLETED,
            pageable);
}

@Query("""
        select new com.readum.model.aiChat.repository.projection.AiChatSessionListProjection(
                   aiChatSession.id
                 , aiChatSession.title
                 , case
                       when aiChatSession.status = :lockedStatus then 'SUMMARIZING'
                       when exists (
                                select 1 from Summary summary
                                 where summary.aiChatSessionId = aiChatSession.id
                            ) then 'SUMMARIZED'
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
                 select 1 from UserBook userBook
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
        Pageable pageable
);
```

> 참고: dev 는 Summary 와 left join 했지만, 최신 1건 매칭/중복행 회피를 위해 여기서는 `exists` 서브쿼리로 "감상문 존재 여부"만 본다(상태가 사라져 존재 여부만 필요).

- [ ] **Step 2: 스케줄러 대상 선정 쿼리 교체**

dev 의 `findByStatusAndAccumulatedTokensGreaterThanEqualAndUpdatedAtAfter` 를 제거하고, **마지막 COMPLETED 메시지가 24h 이내 + 누적 토큰 ≥ 임계값** 인 ACTIVE 세션 id 를 반환하는 쿼리로 교체. `updatedAt` 대신 메시지 시각 사용.

```java
/** 자동 요약 대상 세션 id — ACTIVE + 누적 토큰 ≥ minTokens + 마지막 COMPLETED 메시지가 since 이후. */
@Query("""
        select aiChatSession.id
          from AiChatSession aiChatSession
         where aiChatSession.status = :activeStatus
           and aiChatSession.accumulatedTokens >= :minTokens
           and exists (
                 select 1 from AiChatMessage aiChatMessage
                  where aiChatMessage.sessionId = aiChatSession.id
                    and aiChatMessage.status = :messageCompletedStatus
                    and aiChatMessage.createdAt >= :since
               )
        """)
List<Long> findAutoSummaryTargetSessionIds(
        @Param("activeStatus") AiChatSession.Status activeStatus,
        @Param("minTokens") int minTokens,
        @Param("messageCompletedStatus") AiChatMessage.Status messageCompletedStatus,
        @Param("since") LocalDateTime since);
```

`findByIdAndOwner`, `findByIdForUpdate`, `deleteAllByUserBookId` 는 dev 그대로 유지.

---

## Task 7: 도메인 DTO·정책·에러코드·표시상태 enum

**파일:**
- Modify `src/main/java/com/readum/domain/aiChat/dto/AiChatSessionDisplayStatus.java`
- Modify `src/main/java/com/readum/domain/aiChat/dto/SummaryDraftEligibility.java`
- Modify `src/main/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicy.java`
- Modify `src/main/java/com/readum/domain/aiChat/exception/AiChatErrorCode.java`

- [ ] **Step 1: `AiChatSessionDisplayStatus`** = `{ ACTIVE, SUMMARIZING, SUMMARIZED }`. (FAILED·CLOSED 제거)

- [ ] **Step 2: `SummaryDraftEligibility.IneligibleReason`** 에서 `SESSION_ALREADY_CLOSED` → `SUMMARY_IN_PROGRESS`. `CHAT_VOLUME_NOT_ENOUGH` 유지.

- [ ] **Step 3: `SummaryDraftPolicy`** — 세션 상태 switch 를 LOCKED 기준으로:

```java
public SummaryDraftEligibility evaluate(AiChatSession session) {
    return switch (session.getStatus()) {
        case LOCKED -> SummaryDraftEligibility.fail(IneligibleReason.SUMMARY_IN_PROGRESS);
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
        case SUMMARY_IN_PROGRESS -> new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        case CHAT_VOLUME_NOT_ENOUGH -> new UnprocessableEntityException(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
    };
}
```

`isEligible(int)`, `calculateProgressPercent(int)`, `MIN_ACCUMULATED_TOKENS` 유지(스케줄러·진행률이 사용).

- [ ] **Step 4: `AiChatErrorCode`** 최종 집합 — 삭제: `SESSION_CLOSED`(→`SESSION_LOCKED` 로 의미 교체), `SESSION_ALREADY_CLOSED`, `SUMMARY_GENERATION_FAILED`, `SUMMARY_NOT_COMPLETED`. 추가/유지:

```java
SESSION_LOCKED("감상문 생성 중에는 메시지를 보낼 수 없습니다."),
...
SUMMARY_NOT_FOUND("아직 생성된 감상문이 없습니다."),
SUMMARY_IN_PROGRESS("감상문을 생성 중입니다. 잠시 후 다시 시도해 주세요."),
```

(나머지 USER_BOOK_NOT_FOUND, SESSION_NOT_FOUND, CHAT_VOLUME_NOT_ENOUGH, MESSAGE_*, 가드레일/AI 관련 코드는 dev 그대로 유지)

---

## Task 8: `SummaryDraftService` — lock/unlock, 실패 행 없음, 전체 대화 재요약

**파일:** Modify `src/main/java/com/readum/domain/aiChat/service/SummaryDraftService.java`

- [ ] **Step 1: 전체 재작성**

```java
@Slf4j
@Service
public class SummaryDraftService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiSummaryClient aiSummaryClient;
    private final UserBookRepository userBookRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final TransactionTemplate transactionTemplate;

    public SummaryDraftService( /* dev 와 동일한 8개 생성자 인자 */ ) { /* 동일 대입 */ }

    /**
     * TX1(검증 + 세션 잠금) 동기 완료 후 즉시 반환. LLM 호출·결과 기록은 @Async 백그라운드.
     * "생성 중" 은 세션 LOCKED 가 표현하고, 감상문 행은 미리 만들지 않는다.
     * 성공 시에만 COMPLETED 행을 새로 쓴다(write-once). 실패 시 행 없이 로그만 남기고 unlock.
     */
    public void execute(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        PreparedContext preparedContext = transactionTemplate.execute(status -> {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
            userBookRepository.findByIdAndUserId(session.getUserBookId(), user.getId())
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
            summaryDraftPolicy.assertEligible(session);

            List<AiChatMessage> messages =
                    aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);
            session.lock();
            return new PreparedContext(session.getUserBookId(), messages);
        });

        generateAsync(sessionId, preparedContext);
    }

    @Async
    public void generateAsync(Long sessionId, PreparedContext preparedContext) {
        generateAndRecord(sessionId, preparedContext);
    }

    /**
     * 스케줄러 전용. 인증 없이 세션 id 로 동작. 세션 전체 대화로 재요약한다(증분 아님).
     * 수동 경로와 동일하게 lock → 생성 → 성공 시 새 행 + unlock / 실패 시 로그 + unlock.
     */
    public void executeForScheduler(Long sessionId) {
        PreparedContext preparedContext = transactionTemplate.execute(status -> {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
            if (session.isLocked()) {
                return null; // 이미 다른 생성이 진행 중 — 건너뜀
            }
            List<AiChatMessage> messages =
                    aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);
            session.lock();
            return new PreparedContext(session.getUserBookId(), messages);
        });
        if (preparedContext == null) {
            return;
        }
        generateAndRecord(sessionId, preparedContext);
    }

    /** 공통: TX 밖 LLM 호출 → 성공 시 COMPLETED 행 + unlock(한 TX), 실패 시 로그 + unlock(행 없음). */
    private void generateAndRecord(Long sessionId, PreparedContext preparedContext) {
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(preparedContext.messages());
        } catch (Exception e) {
            log.error("감상문 생성 실패 sessionId={}", sessionId, e);
            transactionTemplate.executeWithoutResult(status ->
                    aiChatSessionRepository.findById(sessionId).ifPresentOrElse(
                            AiChatSession::unlock,
                            () -> log.warn("생성 종료 후 잠금 해제할 세션을 찾지 못함 sessionId={}", sessionId)));
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            summaryRepository.save(Summary.createCompleted(
                    preparedContext.userBookId(), sessionId, result.title(), result.body()));
            aiChatSessionRepository.findById(sessionId).ifPresentOrElse(
                    AiChatSession::unlock,
                    () -> log.warn("생성 종료 후 잠금 해제할 세션을 찾지 못함 sessionId={}", sessionId));
        });
    }

    public record PreparedContext(Long userBookId, List<AiChatMessage> messages) {}
}
```

제거: dev 의 `retryForScheduler`, `generateAndUpdateStatus`, `LocalDate`/`summaryDate` 관련, IN_PROGRESS 선점 로직. (스케줄러는 더 이상 `summaryDate` 를 넘기지 않는다)

---

## Task 9: 조회·편집·기록·메시지전송 서비스

**파일:**
- Modify `src/main/java/com/readum/domain/aiChat/service/SummarySearchService.java`
- Modify `src/main/java/com/readum/domain/aiChat/service/SummaryEditService.java`
- Modify `src/main/java/com/readum/domain/summary/service/SummaryHistorySearchService.java`
- Modify `src/main/java/com/readum/domain/aiChat/service/AiChatMessagePersistService.java`

- [ ] **Step 1: `SummarySearchService`** — 세션 LOCKED → 409, 최신 행 → 200, 없음 → 404:

```java
public SummaryResult findBySessionId(Long sessionId, String userSessionId) {
    User user = userRepository.findBySessionId(userSessionId)
            .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

    AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, user.getId())
            .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

    if (session.isLocked()) {
        throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
    }
    Summary summary = summaryRepository.findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(sessionId)
            .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SUMMARY_NOT_FOUND));
    return SummaryResult.from(summary);
}
```

> `findByIdAndOwner` 가 엔티티를 반환하도록 dev 시그니처 확인(현재 `Optional<AiChatSession>` 반환 — OK). status switch(IN_PROGRESS/FAILED) 제거.

- [ ] **Step 2: `SummaryEditService`** — `isCompleted()` 가드와 `SUMMARY_NOT_COMPLETED` 분기 제거(모든 행이 성공 기록). 최신 행 조회를 `findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc` 로 교체. 나머지 흐름(소유권 검증 → `summary.edit()` → `SummaryResult.from`) 유지. `BadRequestException` import 가 더 안 쓰이면 제거.

- [ ] **Step 3: `SummaryHistorySearchService`** — `findCompletedHistoryByUserId` 호출을 `findLatestHistoryByUserId` 로 교체. 주석의 "종료(CLOSED)된 세션" 문구를 "세션당 최신 감상문 1건" 으로 정정. 나머지(페이지 크기 20, Slice→Result) 유지.

- [ ] **Step 4: `AiChatMessagePersistService`** — 세션 차단 검증을 `isClosed()`/`SESSION_CLOSED` → `isLocked()`/`SESSION_LOCKED` 로 교체. (자동병합 결과 확인해 정리)

---

## Task 10: `SummaryScheduler` — 새 대상 선정 + 재시도 스케줄러 제거

**파일:** Modify `src/main/java/com/readum/infrastructure/aiChat/scheduler/SummaryScheduler.java`

- [ ] **Step 1: 재작성**

```java
@Slf4j
@Component
public class SummaryScheduler {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final SummaryDraftService summaryDraftService;
    private final Executor summaryExecutor;

    public SummaryScheduler(
            AiChatSessionRepository aiChatSessionRepository,
            SummaryDraftService summaryDraftService,
            @Qualifier("summaryExecutor") Executor summaryExecutor
    ) {
        this.aiChatSessionRepository = aiChatSessionRepository;
        this.summaryDraftService = summaryDraftService;
        this.summaryExecutor = summaryExecutor;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void generateDailySummaries() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Long> targetSessionIds = aiChatSessionRepository.findAutoSummaryTargetSessionIds(
                AiChatSession.Status.ACTIVE,
                SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS,
                AiChatMessage.Status.COMPLETED,
                since);
        log.info("독후감 자동 생성 스케줄러 시작 대상 {}건", targetSessionIds.size());

        List<CompletableFuture<Void>> futures = targetSessionIds.stream()
                .map(sessionId -> CompletableFuture.runAsync(
                        () -> executeSafely(sessionId), summaryExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("독후감 자동 생성 스케줄러 완료");
    }

    private void executeSafely(Long sessionId) {
        try {
            summaryDraftService.executeForScheduler(sessionId);
        } catch (Exception e) {
            log.error("독후감 자동 생성 실패 sessionId={}", sessionId, e);
        }
    }
}
```

제거: `retryFailedSummaries()`, `findEligibleSessionIds()`(델타 토큰 필터), `MAX_RETRY_COUNT`, `SummaryRepository`/`AiChatMessageRepository`/`SummaryDraftPolicy` 의존(대상 쿼리가 repository 로 흡수돼 불필요). `SummarySchedulerConfig`(executor bean) 는 그대로 유지.

---

## Task 11: 신규 기능 — 책별 대화 세션 목록 (도메인·조회)

**파일:**
- Create `src/main/java/com/readum/domain/aiChat/dto/BookChatSessionsResult.java`
- Create `src/main/java/com/readum/domain/aiChat/service/BookChatSessionSearchService.java`
- (확인) `src/main/java/com/readum/model/book/repository/BookRepository.java` 에 `findById` (JpaRepository 기본) 사용

- [ ] **Step 1: Result DTO (도메인)**

```java
package com.readum.domain.aiChat.dto;

import com.readum.model.book.entity.Book;

import java.time.LocalDate;
import java.util.List;

public record BookChatSessionsResult(
        BookInfo book,
        List<SessionItem> sessions
) {
    public record BookInfo(String title, Integer publishedYear, String publisher, String coverImageUrl) {
        public static BookInfo from(Book book) {
            return new BookInfo(book.getTitle(), book.getPublishedYear(), book.getPublisher(), book.getCoverUrl());
        }
    }

    public record SessionItem(Long sessionId, String latestSummaryContent, LocalDate lastChattedDate) {}
}
```

- [ ] **Step 2: 조회 서비스 (service 가 각 repository 호출로 합성 — repository 는 자기 엔티티만 반환)**

흐름: 인증 → userBook 소유권 검증(`userBookRepository.findByIdAndUserId`) → Book 조회 → 세션 목록(userBookId) → 세션별 마지막 채팅일(메시지 집계) → 세션별 최신 감상문 본문(`summaryRepository.findLatestByAiChatSessionIdIn`) → 합성, 마지막 채팅일 최신순 정렬.

```java
@Service
@RequiredArgsConstructor
public class BookChatSessionSearchService {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final SummaryRepository summaryRepository;

    public BookChatSessionsResult findByUserBook(Long userBookId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));
        UserBook userBook = userBookRepository.findByIdAndUserId(userBookId, user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));
        Book book = bookRepository.findById(userBook.getBookId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        List<AiChatSession> sessions = aiChatSessionRepository.findByUserBookId(userBookId);
        List<Long> sessionIds = sessions.stream().map(AiChatSession::getId).toList();

        Map<Long, String> latestSummaryBySession = summaryRepository.findLatestByAiChatSessionIdIn(sessionIds)
                .stream().collect(Collectors.toMap(Summary::getAiChatSessionId, Summary::getBody));
        Map<Long, LocalDate> lastChattedBySession = lastChattedDates(sessionIds);

        List<BookChatSessionsResult.SessionItem> items = sessions.stream()
                .map(session -> new BookChatSessionsResult.SessionItem(
                        session.getId(),
                        latestSummaryBySession.get(session.getId()),
                        lastChattedBySession.getOrDefault(session.getId(), session.getCreatedAt().toLocalDate())))
                .sorted(Comparator.comparing(BookChatSessionsResult.SessionItem::lastChattedDate).reversed())
                .toList();

        return new BookChatSessionsResult(BookChatSessionsResult.BookInfo.from(book), items);
    }

    private Map<Long, LocalDate> lastChattedDates(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return aiChatMessageRepository.findLastChattedAtBySessionIds(sessionIds, AiChatMessage.Status.COMPLETED)
                .stream().collect(Collectors.toMap(
                        SessionLastChattedProjection::sessionId,
                        row -> row.lastChattedAt().toLocalDate()));
    }
}
```

- [ ] **Step 3: 보조 쿼리 추가** (자기 엔티티 집계만 반환)

`AiChatSessionRepository` 에:
```java
List<AiChatSession> findByUserBookId(Long userBookId);
```

`AiChatMessageRepository` 에 세션별 마지막 COMPLETED 메시지 시각 집계 + 프로젝션:
```java
@Query("""
        select aiChatMessage.sessionId as sessionId
             , max(aiChatMessage.createdAt) as lastChattedAt
          from AiChatMessage aiChatMessage
         where aiChatMessage.sessionId in :sessionIds
           and aiChatMessage.status = :status
         group by aiChatMessage.sessionId
        """)
List<SessionLastChattedProjection> findLastChattedAtBySessionIds(
        @Param("sessionIds") Collection<Long> sessionIds,
        @Param("status") AiChatMessage.Status status);
```

Create `src/main/java/com/readum/model/aiChat/repository/projection/SessionLastChattedProjection.java`:
```java
package com.readum.model.aiChat.repository.projection;

import java.time.LocalDateTime;

public interface SessionLastChattedProjection {
    Long sessionId();
    LocalDateTime lastChattedAt();
}
```
(인터페이스 기반 projection — alias `sessionId`/`lastChattedAt` 와 메서드명이 일치해야 함)

---

## Task 12: 신규 기능 — 컨트롤러·응답 DTO

**파일:**
- Create `src/main/java/com/readum/presentation/controller/aiChat/dto/BookChatSessionsResponse.java`
- Modify `src/main/java/com/readum/presentation/controller/aiChat/AiChatController.java`

- [ ] **Step 1: Response DTO**

```java
package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.BookChatSessionsResult;

import java.time.LocalDate;
import java.util.List;

public record BookChatSessionsResponse(
        BookResponse book,
        List<SessionResponse> sessions
) {
    public record BookResponse(String title, Integer publishedYear, String publisher, String coverImageUrl) {}
    public record SessionResponse(Long sessionId, String latestSummaryContent, LocalDate lastChattedDate) {}

    public static BookChatSessionsResponse from(BookChatSessionsResult result) {
        BookChatSessionsResult.BookInfo book = result.book();
        return new BookChatSessionsResponse(
                new BookResponse(book.title(), book.publishedYear(), book.publisher(), book.coverImageUrl()),
                result.sessions().stream()
                        .map(s -> new SessionResponse(s.sessionId(), s.latestSummaryContent(), s.lastChattedDate()))
                        .toList());
    }
}
```

- [ ] **Step 2: 컨트롤러 엔드포인트 추가** (`@Tag`/`@Operation`/`@ApiResponses` 컨벤션, `GlobalApiResponse`):

```java
@Operation(summary = "책별 대화 세션 목록",
        description = "한 권(userBook)에 대해 만든 모든 채팅 세션을 책 정보·세션별 최신 감상문 본문·마지막 대화일과 함께 최신순으로 조회한다.")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "401", description = "인증 실패"),
        @ApiResponse(responseCode = "404", description = "본인 책장의 책이 아님")
})
@GetMapping("/books/{userBookId}/sessions")
public ResponseEntity<GlobalApiResponse<BookChatSessionsResponse>> getBookChatSessions(
        @PathVariable Long userBookId,
        @CookieValue(name = "user_session") String userSessionId
) {
    BookChatSessionsResult result = bookChatSessionSearchService.findByUserBook(userBookId, userSessionId);
    return GlobalApiResponse.ok(BookChatSessionsResponse.from(result));
}
```

(쿠키 인증 패턴은 같은 컨트롤러의 기존 엔드포인트와 동일하게 맞춘다 — `@CookieValue` 이름·Request DTO 래핑 여부 확인)

- [ ] **Step 3: 기존 엔드포인트 swagger 정리** — 자동병합된 `getSummary`/`createSummaryDraft`/`getSummaryDraftEligibility`/`sendMessage` 의 설명에서 CLOSED/생성 실패 문구를 LOCKED/새 모델 문구로 교체(설계 문서 5·6·7절 기준). `editSummary` 는 그대로.

---

## Task 13: 컴파일 정합성 — 전체 빌드 1차

- [ ] **Step 1:** `./gradlew compileJava` 실행, 남은 참조 오류(삭제된 메서드/enum/에러코드 호출처) 정리. 특히:
  - `Summary.Status`/`createInProgress`/`complete`/`fail` 등 호출처 잔존 여부
  - `AiChatSession.close()`/`isClosed()` 호출처
  - `AiChatErrorCode.SESSION_CLOSED`/`SESSION_ALREADY_CLOSED`/`SUMMARY_GENERATION_FAILED`/`SUMMARY_NOT_COMPLETED` 참조
  - 스케줄러가 제거한 의존 주입처

---

## Task 14: 테스트 재작성·추가

**파일(주요):**
- `src/test/java/com/readum/model/summary/entity/SummaryFixture.java`
- `src/test/java/com/readum/model/aiChat/entity/AiChatSessionFixture.java`
- `src/test/.../SummaryDraftServiceTest.java`, `SummarySearchServiceTest.java`, `SummaryEditServiceTest.java`
- `src/test/.../SummaryDraftPolicyTest.java`, `AiChatMessagePersistServiceTest.java`
- `src/test/.../SummaryRepositoryTest.java`, `AiChatSessionRepositoryTest.java`
- `src/test/.../AiChatControllerTest.java`, `SummaryControllerTest.java`(history), 스케줄러 테스트
- Create `BookChatSessionSearchServiceTest.java` + DAO 통합 테스트

- [ ] **Step 1: 픽스처 정리**
  - `SummaryFixture`: dev 의 status/summaryDate/retryCount 인자를 제거하고 새 생성자 `(id, userBookId, aiChatSessionId, quote, title, body, createdAt, updatedAt)` 에 맞춘 명명 팩토리만 — `persistedSummary(id, userBookId, sessionId, title, body)`(quote=null). `persistedFailedSummary` 제거(실패 행 개념 없음).
  - `AiChatSessionFixture`: `persistedClosedSession` → `persistedLockedSession`(LOCKED), `persistedActiveSession` 유지.

- [ ] **Step 2: 단위 테스트 갱신** — 설계 문서 "테스트 계획" 의 시나리오를 단언으로:
  - 생성 성공 → 세션 ACTIVE 복귀 + COMPLETED 행 1개 추가 (Mockito: `aiSummaryClient.generate` stub, `summaryRepository.save` 캡처, 세션 unlock 검증)
  - 생성 실패 → 세션 ACTIVE 복귀 + **`summaryRepository.save` 호출 없음**(`verify(never())`) + ERROR 로그
  - LOCKED 세션 메시지 전송 → 400 `SESSION_LOCKED`
  - LOCKED 세션 생성 요청 → 409 `SUMMARY_IN_PROGRESS` (policy)
  - `SummarySearchService`: LOCKED→409 / 최신 행→200 / 없음→404
  - 편집: 최신 행 `edit` 호출 검증
  - 예외 단언은 `asInstanceOf(InstanceOfAssertFactories.type(...))` + 메서드 레퍼런스 (리플렉션 문자열 키 금지)

- [ ] **Step 3: DAO 통합 테스트** (H2)
  - `SummaryRepositoryTest`: 한 세션에 여러 행 저장 → `findFirstBy...DescIdDesc` 가 최신 반환; `findLatestByAiChatSessionIdIn` 가 세션별 최신 1건; `findLatestHistoryByUserId` 가 세션당 1건·최신순·타 사용자 제외
  - `AiChatSessionRepositoryTest`: 표시상태 3종(LOCKED→SUMMARIZING / 감상문 있음→SUMMARIZED / 없음→ACTIVE); `findAutoSummaryTargetSessionIds` 가 (24h 내 메시지 + 누적토큰≥500) 세션만, 24h 초과·토큰부족 세션 제외
  - `BookChatSessionSearchServiceTest`(통합): 책 정보 + 세션별 최신 감상문 본문(없으면 null) + 마지막 채팅일 최신순; 타인 userBook 은 404

- [ ] **Step 4: 컨트롤러 테스트** — `AiChatControllerTest` 의 CLOSED/실패 전제 시나리오를 LOCKED/404 로 교체, 신규 `GET /books/{userBookId}/sessions` 200/401/404 추가. `SummaryControllerTest`(있다면) history 최신-1건 응답 확인.

- [ ] **Step 5:** `./gradlew test` 전체 통과. (기존 flaky `AiChatMessageSendServiceTest.스트림_도중_에러...` 는 별개 이슈 — 반복 실패 시 재확인하되 이번 범위 아님)

---

## Task 15: 병합 커밋 + 신규 기능 커밋 + 최종 빌드

- [ ] **Step 1:** `./gradlew clean build` 통과 확인.
- [ ] **Step 2:** Task 1~10, 13~14 의 모델 통일분을 **merge commit** 으로 마무리:
  ```bash
  git add -A
  git commit --no-edit   # 병합 커밋 메시지에 통합 요약 한 줄 추가
  ```
- [ ] **Step 3:** 책별 세션 목록(Task 11~12 + 해당 테스트)이 같은 merge 에 섞였다면 그대로 두고, 분리 가능하면 별도 커밋. (실용상 merge 해소가 한 덩이라 한 커밋도 허용)
- [ ] **Step 4:** `superpowers:finishing-a-development-branch` 로 마무리(테스트 통과 확인 → 옵션 제시). PR 본문은 기존 PR #75 갱신 또는 신규.

---

## Self-Review 메모

- 스펙 커버리지: 설계 문서 1~9절 + 신규 기능 → Task 2~12 매핑 완료.
- 타입 일관성: `findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc` 이름이 Repository/Search/Edit 에서 동일. `createCompleted(userBookId, sessionId, title, body)` 시그니처가 Service/Fixture 에서 동일. `BookChatSessionsResult` 필드명이 도메인↔응답 DTO 에서 대응.
- 미해결로 남긴 판단: 표시상태 CASE 를 left join 대신 `exists` 서브쿼리로 단순화(Step 6-1) — DAO 테스트로 검증. `SessionLastChattedProjection` 인터페이스 alias 일치 필요.
