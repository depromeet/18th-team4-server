# 독후감 생성 완료 후 대화 이어가기 (#69) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 감상문 생성이 끝난(성공/실패) 채팅 세션을 자동으로 다시 활성화해 대화를 이어가고, 감상문을 재생성할 수 있게 한다. 과거 감상문은 세션당 여러 행으로 DB 에 이력 보존한다.

**Architecture:** 세션 상태 `CLOSED` 를 `LOCKED`(감상문 생성 중 잠금) 로 바꾸고, 생성이 끝나면 `ACTIVE` 로 자동 복귀한다. `Summary` 는 세션과 1:N 으로 전환하고 `IN_PROGRESS` placeholder 행을 없앤다 — 감상문 행은 생성 시도가 끝난 시점에 결과(COMPLETED/FAILED)와 함께 한 번만 기록되는 불변 데이터가 된다. "현재 감상문" 은 세션의 최신 행이다.

**Tech Stack:** Spring Boot 4.0.5, Java 25, JPA(Hibernate), MySQL 8.4 (dev RDS, `ddl-auto: validate` — 스키마 변경은 수동 SQL), JUnit 5 + Mockito + AssertJ.

**스펙 문서:** `docs/superpowers/specs/2026-06-12-resume-summarized-session-design.md`

**프론트 조율 사항 (구현과 별개로 공유 필요):**
- 세션 목록 표시 상태 값 `CLOSED` → `SUMMARIZED` 변경
- eligibility 응답 reason 값 `SESSION_ALREADY_CLOSED` → `SUMMARY_IN_PROGRESS` 변경
- 감상문 완료 후에도 대화 입력이 가능해지는 화면 흐름

**작업 브랜치:** `feature/69-resume-summarized-session` (이미 생성됨)

---

## 전체 작업 순서와 이유

이 변경은 enum 값 삭제(`Summary.Status.IN_PROGRESS`)가 여러 파일에 한꺼번에 컴파일 오류를 일으키는 구조라, **추가(additive) 작업을 먼저 하고 삭제는 마지막에** 하는 순서로 쪼갠다. 각 Task 종료 시점에 항상 컴파일이 되고 전체 테스트가 통과한다.

1. DB 사전 마이그레이션 — summary unique 제약 제거 (이후 DAO 테스트가 세션당 복수 행을 저장)
2. 세션 상태 `LOCKED` 전환 (rename + 메시지 차단 에러 코드)
3. 생성 자격 정책 변경 (`SESSION_ALREADY_CLOSED` 삭제, `SUMMARY_IN_PROGRESS` 재사용)
4. Summary 신규 팩토리 + 최신 행 조회 repository 메서드 (additive)
5. 세션 목록 표시 상태 `SUMMARIZED` + 최신 Summary join
6. SummaryDraftService 새 트랜잭션 구조 (잠금 → 생성 → 결과 기록 + 잠금 해제)
7. SummarySearchService 새 판정 (세션 LOCKED / 최신 행 기준)
8. `IN_PROGRESS` 잔재 제거 (enum 값, 구 팩토리/변경 메서드, unique 제약 어노테이션)
9. Swagger 문서 갱신
10. 배포 시 데이터 마이그레이션 SQL 작성
11. 전체 검증

**중요 — 테스트 DB (실행 중 정정):** DAO 통합 테스트(`@SpringBootTest`)는 dev RDS 가 아니라 **H2 인메모리** (`src/test/resources/application.yml`, `ddl-auto: create-drop`) 로 돈다 — 스키마가 엔티티 어노테이션에서 생성된다. 따라서 세션당 복수 감상문 테스트(Task 4 이후)를 통과시키려면 `Summary` 엔티티의 `@UniqueConstraint` 어노테이션 제거가 선행되어야 한다 (원래 Task 8 이던 것을 Task 4 로 앞당김). Task 1 의 SQL 은 dev RDS(배포 환경) 전용이며 테스트와 무관 — dev RDS 는 초기화/재생성 가능하므로(사용자 확인) 적용 시점은 자유.

---

### Task 1: DB 사전 마이그레이션 — summary unique 제약 제거

새 모델은 세션당 감상문 여러 행을 허용하므로 `uk_summary_ai_chat_session` 제약을 먼저 제거한다. 이 제약 제거는 구 코드(현재 dev 서버 배포본)와도 호환된다 — 구 코드는 어차피 세션당 두 번째 행을 만들지 않는다.

**Files:**
- Create: `docs/sql/2026-06-12-issue-69-pre-deploy.sql`

- [ ] **Step 1: SQL 파일 작성**

```sql
-- 이슈 #69: 감상문 생성 완료 후 대화 이어가기 — 사전 마이그레이션
-- 적용 시점: feature 브랜치 개발/테스트 시작 전 (코드 배포와 무관하게 먼저 실행 가능)
-- 적용 대상: dev RDS (readum)
--
-- 새 모델은 세션당 감상문(summary) 행을 여러 건 허용한다 (재생성 이력 보존).
-- DAO 통합 테스트가 dev RDS 에 직접 접속해 세션당 복수 행을 저장하므로,
-- 이 제약 제거가 선행되어야 테스트가 통과한다.
-- 구 코드는 세션당 두 번째 행을 만들지 않으므로 이 변경은 구 코드와 호환된다.

ALTER TABLE summary DROP INDEX uk_summary_ai_chat_session;

-- 검증: 아래 결과에 uk_summary_ai_chat_session 이 없어야 한다.
-- SHOW INDEX FROM summary;
```

- [ ] **Step 2: Commit**

```bash
git add docs/sql/2026-06-12-issue-69-pre-deploy.sql
git commit -m "chore(ai-chat): 감상문 unique 제약 제거 사전 마이그레이션 SQL 추가"
```

- [ ] **Step 3: 사용자에게 dev RDS 적용 요청 (체크포인트 — 사람 개입 필요)**

실행 주체가 DB 접속 권한이 없으므로 사용자(미규 님)에게 위 SQL 을 dev RDS 에 실행해 달라고 요청하고, `SHOW INDEX FROM summary;` 결과에 `uk_summary_ai_chat_session` 이 없는 것을 확인받은 뒤 다음 Task 로 진행한다. **이 확인 없이 Task 4 이후로 진행하면 DAO 테스트가 실패한다.**

---

### Task 2: 세션 상태 LOCKED 전환

`AiChatSession.Status.CLOSED` 를 `LOCKED` 로 바꾸고 (의미: 영구 종료 → 감상문 생성 중 잠금), 메서드와 에러 코드를 의미에 맞게 정리한다. 이 Task 는 이름 변경(rename)이라 동작이 변하지 않는다 — 기존 테스트가 이름만 바뀐 채 모두 통과해야 한다.

**Files:**
- Modify: `src/main/java/com/readum/model/aiChat/entity/AiChatSession.java`
- Modify: `src/main/java/com/readum/domain/aiChat/exception/AiChatErrorCode.java`
- Modify: `src/main/java/com/readum/domain/aiChat/service/AiChatMessagePersistService.java`
- Modify: `src/main/java/com/readum/domain/aiChat/service/SummaryDraftService.java`
- Modify: `src/main/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicy.java`
- Test (모두 기존 파일 수정): `AiChatSessionFixture`, `AiChatMessagePersistServiceTest`, `AiChatMessageSendServiceTest`, `AiChatControllerTest`, `SummaryDraftServiceTest`, `SummaryDraftPolicyTest`, `SummaryDraftSearchServiceTest`, `AiChatSessionRepositoryTest`

- [ ] **Step 1: 잠긴 세션 차단 테스트를 새 이름·새 에러 코드로 먼저 수정 (TDD — 컴파일 실패 확인용)**

`src/test/java/com/readum/domain/aiChat/service/AiChatMessagePersistServiceTest.java` 의 기존 테스트(76행 부근)를 다음으로 교체:

```java
@Test
void loadHistory_잠긴_세션이면_BadRequest_SESSION_LOCKED_를_던진다() {
    // 감상문 생성이 도는 동안(LOCKED) 메시지 전송이 차단되는 계약.
    AiChatSession locked = AiChatSessionFixture.persistedLockedSession(
            SESSION_ID, USER_BOOK_ID, 10, 600, "제목"
    );
    given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(locked));

    assertThatThrownBy(() -> aiChatMessagePersistService.loadHistory(SESSION_ID, USER_ID))
            .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
            .extracting(BadRequestException::getErrorCode)
            .isEqualTo(AiChatErrorCode.SESSION_LOCKED);
}
```

(기존 테스트의 상수/스텁 구조는 그대로 두고 fixture 호출명, 테스트명, 에러 코드만 바꾼다. 기존 파일의 변수명·상수가 위와 다르면 기존 것을 따른다.)

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `persistedLockedSession`, `SESSION_LOCKED` 미정의

- [ ] **Step 3: AiChatSession 엔티티 변경**

`AiChatSession.java` 에서:

```java
public enum Status {
    ACTIVE, LOCKED
}
```

`close()` / `isClosed()` 를 다음으로 교체:

```java
/**
 * 감상문 생성이 도는 동안 세션을 잠가 메시지 전송과 중복 생성 요청을 막는다.
 */
public void lock() {
    this.status = Status.LOCKED;
    this.updatedAt = LocalDateTime.now();
}

/**
 * 감상문 생성이 끝나면(성공/실패 무관) 세션을 다시 활성화해 대화를 이어갈 수 있게 한다.
 */
public void unlock() {
    this.status = Status.ACTIVE;
    this.updatedAt = LocalDateTime.now();
}
```

```java
public boolean isLocked() {
    return this.status == Status.LOCKED;
}
```

- [ ] **Step 4: 에러 코드 변경**

`AiChatErrorCode.java` 9행:

```java
// 변경 전
SESSION_CLOSED("종료된 세션에는 메시지를 보낼 수 없습니다."),
// 변경 후
SESSION_LOCKED("감상문 생성 중에는 메시지를 보낼 수 없습니다."),
```

(`SESSION_ALREADY_CLOSED` 는 Task 3 에서 삭제하므로 여기서는 그대로 둔다.)

- [ ] **Step 5: main 호출부 일괄 수정**

| 파일 | 변경 전 | 변경 후 |
|---|---|---|
| `AiChatMessagePersistService.java` 41행 javadoc | `세션 소유권 및 종료 여부를 검증하고` | `세션 소유권 및 잠김 여부를 검증하고` |
| `AiChatMessagePersistService.java` 50-51행 | `if (session.isClosed()) { throw new BadRequestException(AiChatErrorCode.SESSION_CLOSED);` | `if (session.isLocked()) { throw new BadRequestException(AiChatErrorCode.SESSION_LOCKED);` |
| `SummaryDraftService.java` 81행 | `session.close();` | `session.lock();` |
| `SummaryDraftService.java` 68행 주석 | `// TX1: 검증 + 세션 종료 + Summary(IN_PROGRESS) 선점 — 커밋 후 즉시 반환` | `// TX1: 검증 + 세션 잠금 + Summary(IN_PROGRESS) 선점 — 커밋 후 즉시 반환` |
| `SummaryDraftPolicy.java` 19행 | `case CLOSED ->` | `case LOCKED ->` |

- [ ] **Step 6: 테스트 픽스처 rename**

`src/test/java/com/readum/model/aiChat/entity/AiChatSessionFixture.java` 36행 부근:

```java
/**
 * 저장되어 id 가 부여된, 감상문 생성 중 잠긴(LOCKED) 세션.
 */
public static AiChatSession persistedLockedSession(
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

클래스 javadoc 의 `(활성/종료)` 는 `(활성/잠김)` 으로 수정.

- [ ] **Step 7: 나머지 테스트 호출부 일괄 수정 (동작 동일, 식별자만 교체)**

| 파일:행 | 변경 내용 |
|---|---|
| `AiChatMessageSendServiceTest.java` 270-278행 | 테스트명 `사전_단계에서_BadRequestException_SESSION_CLOSED_도_그대로_전파된다` → `..._SESSION_LOCKED_...`, `AiChatErrorCode.SESSION_CLOSED` 2곳 → `SESSION_LOCKED` |
| `AiChatControllerTest.java` 314행 | `AiChatErrorCode.SESSION_CLOSED` → `SESSION_LOCKED` (주변 테스트명에 '종료'가 있으면 '잠긴'으로) |
| `SummaryDraftServiceTest.java` 172-186행 | 테스트명 `이미_닫힌_세션이면_ConflictException이_발생한다` → `잠긴_세션이면_ConflictException이_발생한다`, 변수명 `closedSession` → `lockedSession`, `persistedClosedSession` → `persistedLockedSession` (단언의 `SESSION_ALREADY_CLOSED` 는 Task 3 에서 변경) |
| `SummaryDraftPolicyTest.java` 36, 90, 100행 | 테스트명 `종료된_세션이면_...` → `잠긴_세션이면_...`, helper `persistedClosedSession` → `persistedLockedSession` (reason 단언은 Task 3 에서 변경) |
| `SummaryDraftSearchServiceTest.java` 161행 | `persistedClosedSession` → `persistedLockedSession` |
| `AiChatSessionRepositoryTest.java` 152, 167, 184, 201행 | `AiChatSession.Status.CLOSED` → `Status.LOCKED` |
| `AiChatSessionRepositoryTest.java` 262-263행 (saveSession helper) | `if (status == AiChatSession.Status.CLOSED) { session.close(); }` → `if (status == AiChatSession.Status.LOCKED) { session.lock(); }` |

- [ ] **Step 8: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: PASS — rename 만 했으므로 모든 기존 테스트 통과 (표시 상태 JPQL 의 CASE 출력 문자열은 아직 그대로라 DAO 테스트도 통과)

- [ ] **Step 9: Commit**

```bash
git add -A src docs
git commit -m "refactor(ai-chat): 세션 상태 CLOSED 를 LOCKED 로 변경하고 잠금 의미로 정리"
```

---

### Task 3: 생성 자격 정책 변경 — SESSION_ALREADY_CLOSED 삭제

새 모델에서 "이미 감상문이 작성돼서 거절" 시나리오는 사라지고, 남는 거절 사유는 "지금 생성 중(LOCKED)" 뿐이다. 기존 `SUMMARY_IN_PROGRESS` 에러 코드를 재사용한다.

**Files:**
- Modify: `src/main/java/com/readum/domain/aiChat/dto/SummaryDraftEligibility.java`
- Modify: `src/main/java/com/readum/domain/aiChat/service/policy/SummaryDraftPolicy.java`
- Modify: `src/main/java/com/readum/domain/aiChat/exception/AiChatErrorCode.java`
- Test: `SummaryDraftPolicyTest`, `SummaryDraftSearchServiceTest`, `SummaryDraftServiceTest`, `AiChatControllerTest`

- [ ] **Step 1: 정책 테스트를 새 reason 으로 먼저 수정 (TDD)**

`SummaryDraftPolicyTest.java`:
- 36행 부근 테스트명 `잠긴_세션이면_SESSION_ALREADY_CLOSED_사유로_evaluate된다` → `잠긴_세션이면_SUMMARY_IN_PROGRESS_사유로_evaluate된다`
- 42행 `IneligibleReason.SESSION_ALREADY_CLOSED` → `IneligibleReason.SUMMARY_IN_PROGRESS`
- 52행 `AiChatErrorCode.SESSION_ALREADY_CLOSED` → `AiChatErrorCode.SUMMARY_IN_PROGRESS`
- 90행 `IneligibleReason.SESSION_ALREADY_CLOSED` → `IneligibleReason.SUMMARY_IN_PROGRESS`

- [ ] **Step 2: 테스트 실패(컴파일 오류) 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `IneligibleReason.SUMMARY_IN_PROGRESS` 미정의

- [ ] **Step 3: IneligibleReason / Policy 구현**

`SummaryDraftEligibility.java` 의 enum:

```java
public enum IneligibleReason {
    SUMMARY_IN_PROGRESS(AiChatErrorCode.SUMMARY_IN_PROGRESS),
    CHAT_VOLUME_NOT_ENOUGH(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
    // (생성자/getter 는 기존 그대로)
}
```

`SummaryDraftPolicy.java`:

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

`AiChatErrorCode.java` 10행에서 `SESSION_ALREADY_CLOSED("이미 감상문이 작성된 세션입니다."),` 행 삭제.

- [ ] **Step 4: 나머지 테스트 호출부 수정**

| 파일:행 | 변경 내용 |
|---|---|
| `SummaryDraftServiceTest.java` 182행 | `.isEqualTo(AiChatErrorCode.SESSION_ALREADY_CLOSED)` → `.isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS)` |
| `SummaryDraftSearchServiceTest.java` 117-126행 | 테스트명 `이미_종료된_세션이면_eligible_false와_SESSION_ALREADY_CLOSED를_반환한다` → `잠긴_세션이면_eligible_false와_SUMMARY_IN_PROGRESS를_반환한다`, `IneligibleReason.SESSION_ALREADY_CLOSED.name()` → `IneligibleReason.SUMMARY_IN_PROGRESS.name()`, `AiChatErrorCode.SESSION_ALREADY_CLOSED.getMessage()` → `AiChatErrorCode.SUMMARY_IN_PROGRESS.getMessage()` |
| `AiChatControllerTest.java` 513-525행 | 테스트명 `eligibility_종료된_세션이면_200과_SESSION_ALREADY_CLOSED를_반환한다` → `eligibility_잠긴_세션이면_200과_SUMMARY_IN_PROGRESS를_반환한다`, `IneligibleReason.SESSION_ALREADY_CLOSED.name()` / `.getMessage()` / `jsonPath` 의 `"SESSION_ALREADY_CLOSED"` → `SUMMARY_IN_PROGRESS` 계열로 교체 |
| `AiChatControllerTest.java` 579행 | `new ConflictException(AiChatErrorCode.SESSION_ALREADY_CLOSED)` → `new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS)` |

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "refactor(ai-chat): 감상문 생성 거절 사유를 SUMMARY_IN_PROGRESS 로 통일하고 SESSION_ALREADY_CLOSED 삭제"
```

---

### Task 4: Summary 신규 팩토리 + 최신 행 조회 repository 메서드 (additive)

write-once 감상문을 만들 도메인 팩토리 2개와, 세션의 최신 감상문 한 건을 조회하는 derived query 를 추가한다. 기존 코드는 건드리지 않는다 (구 팩토리·메서드는 Task 8 에서 제거).

**Files:**
- Modify: `src/main/java/com/readum/model/summary/entity/Summary.java`
- Modify: `src/main/java/com/readum/model/summary/repository/SummaryRepository.java`
- Modify: `src/test/java/com/readum/model/summary/entity/SummaryFixture.java`
- Create: `src/test/java/com/readum/model/summary/repository/SummaryRepositoryTest.java`

- [ ] **Step 1: DAO 통합 테스트 작성 (TDD)**

```java
package com.readum.model.summary.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SummaryRepositoryTest {

    @Autowired
    private SummaryRepository summaryRepository;

    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;

    @Autowired
    private UserBookRepository userBookRepository;

    private static long userIdSeq = 9_200_000L;
    private static long bookIdSeq = 9_200_000L;

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }

    private static synchronized Long nextBookId() {
        bookIdSeq += 1;
        return bookIdSeq;
    }

    @Test
    @DisplayName("findTopByAiChatSessionIdOrderByIdDesc: 같은 세션의 여러 감상문 중 가장 최근 행을 반환한다")
    void 최신_감상문_조회() {
        UserBook userBook = userBookRepository.save(UserBook.create(nextUserId(), nextBookId()));
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBook.getId()));

        summaryRepository.save(Summary.createFailed(userBook.getId(), session.getId()));
        Summary latest = summaryRepository.save(
                Summary.createCompleted(userBook.getId(), session.getId(), "제목", "본문", "인용"));

        Optional<Summary> found = summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(session.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(latest.getId());
        assertThat(found.get().getStatus()).isEqualTo(Summary.Status.COMPLETED);
    }

    @Test
    @DisplayName("findTopByAiChatSessionIdOrderByIdDesc: 감상문이 없으면 빈 Optional 을 반환한다")
    void 감상문_없으면_빈_Optional() {
        UserBook userBook = userBookRepository.save(UserBook.create(nextUserId(), nextBookId()));
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBook.getId()));

        Optional<Summary> found = summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(session.getId());

        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `createFailed`, `createCompleted`, `findTopByAiChatSessionIdOrderByIdDesc` 미정의

- [ ] **Step 3: Summary 에 write-once 팩토리 추가 + unique 제약 어노테이션 제거**

(실행 중 변경: 테스트 DB 가 H2 + 엔티티 기반 스키마 생성이라, 복수 행 테스트 통과를 위해 `@Table` 의 `uniqueConstraints = { @UniqueConstraint(name = "uk_summary_ai_chat_session", ...) }` 블럭과 `jakarta.persistence.UniqueConstraint` import 제거를 Task 8 에서 이 Task 로 앞당긴다.)

`Summary.java` 의 `createInProgress` 아래에 추가 (기존 메서드는 아직 삭제하지 않음). 전체필드 생성자의 필드 순서는 `(id, userBookId, aiChatSessionId, status, quote, title, body, createdAt, updatedAt)` 이다:

```java
/**
 * 생성 성공으로 끝난 감상문 기록.
 * 감상문 행은 생성 시도가 끝난 시점에 결과와 함께 한 번만 만들어지며 이후 변경되지 않는다.
 * "생성 중" 상태는 이 엔티티가 아니라 세션(AiChatSession.Status.LOCKED) 이 표현한다.
 */
public static Summary createCompleted(
        Long userBookId, Long aiChatSessionId, String title, String body, String quote
) {
    LocalDateTime now = LocalDateTime.now();
    return new Summary(null, userBookId, aiChatSessionId, Status.COMPLETED, quote, title, body, now, now);
}

/**
 * 생성 실패로 끝난 시도의 기록.
 * 세션은 실패 후 다시 활성화되므로, 폴링 응답("생성 실패")의 영속 근거이자 품질 감사용 흔적으로 남긴다.
 */
public static Summary createFailed(Long userBookId, Long aiChatSessionId) {
    LocalDateTime now = LocalDateTime.now();
    return new Summary(null, userBookId, aiChatSessionId, Status.FAILED, null, null, null, now, now);
}
```

- [ ] **Step 4: Repository 에 최신 행 derived query 추가**

`SummaryRepository.java`:

```java
public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findByAiChatSessionId(Long aiChatSessionId);

    /**
     * 세션의 최신 감상문 한 건. 감상문은 세션당 여러 건(재생성 이력) 존재할 수 있고,
     * "현재 감상문" 은 항상 가장 최근 행이다.
     */
    Optional<Summary> findTopByAiChatSessionIdOrderByIdDesc(Long aiChatSessionId);
}
```

(`findByAiChatSessionId` 는 Task 7 에서 호출처가 사라진 뒤 Task 8 에서 삭제한다 — 복수 행 세션에서 단건 조회가 예외를 던지는 위험 메서드가 되기 때문.)

- [ ] **Step 5: SummaryFixture 에 신규 픽스처 추가** (기존 `persistedInProgressSummary` 는 유지, Task 8 에서 삭제)

```java
/**
 * 저장되어 id 가 부여된, 생성 완료(COMPLETED) 감상문.
 */
public static Summary persistedCompletedSummary(
        Long id, Long userBookId, Long aiChatSessionId, String title, String body, String quote
) {
    LocalDateTime now = LocalDateTime.now();
    return new Summary(
            id, userBookId, aiChatSessionId, Summary.Status.COMPLETED,
            quote, title, body, now, now
    );
}

/**
 * 저장되어 id 가 부여된, 생성 실패(FAILED) 감상문.
 */
public static Summary persistedFailedSummary(Long id, Long userBookId, Long aiChatSessionId) {
    LocalDateTime now = LocalDateTime.now();
    return new Summary(
            id, userBookId, aiChatSessionId, Summary.Status.FAILED,
            null, null, null, now, now
    );
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.model.summary.repository.SummaryRepositoryTest"`
Expected: PASS (Task 1 의 unique 제약 제거가 적용된 상태여야 함 — 실패 시 Task 1 체크포인트 재확인)

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "feat(ai-chat): 감상문 write-once 팩토리와 최신 행 조회 쿼리 추가"
```

---

### Task 5: 세션 목록 표시 상태 SUMMARIZED 전환 + 최신 Summary join

표시 상태 산출 규칙을 새 모델에 맞게 바꾼다: 세션 `LOCKED` → SUMMARIZING, `ACTIVE`+감상문 없음 → ACTIVE, `ACTIVE`+최신 COMPLETED → **SUMMARIZED** (기존 CLOSED 대체), `ACTIVE`+최신 FAILED → FAILED. 감상문이 세션당 여러 건이 되므로 join 은 최신 한 건만 매칭한다.

**Files:**
- Modify: `src/main/java/com/readum/domain/aiChat/dto/AiChatSessionDisplayStatus.java`
- Modify: `src/main/java/com/readum/model/aiChat/repository/AiChatSessionRepository.java`
- Modify: `src/main/java/com/readum/model/aiChat/repository/projection/AiChatSessionListProjection.java`
- Test: `src/test/java/com/readum/model/aiChat/repository/AiChatSessionRepositoryTest.java`

- [ ] **Step 1: DAO 테스트의 표시 상태 검증을 새 규칙으로 교체 (TDD)**

`AiChatSessionRepositoryTest.java` 의 상태 도출 테스트 5개(132~208행 부근: `status_ACTIVE_도출`, `status_SUMMARIZING_도출`, `status_CLOSED_with_completed_summary`, `status_FAILED_도출`, `status_CLOSED_without_summary`)를 다음 6개로 교체한다 (`status_ACTIVE_도출` 은 그대로 유지):

```java
@Test
@DisplayName("findSessionsByUserBookIdAndOwner: LOCKED 세션은 'SUMMARIZING' 으로 도출된다")
void status_SUMMARIZING_도출() {
    Long userId = nextUserId();
    UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
    saveSession(userBook.getId(), AiChatSession.Status.LOCKED, "summarizing-session");

    Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
            .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

    assertThat(slice.getContent()).hasSize(1);
    assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZING");
}

@Test
@DisplayName("findSessionsByUserBookIdAndOwner: ACTIVE + 최신 감상문 COMPLETED 면 'SUMMARIZED' 로 도출")
void status_SUMMARIZED_도출() {
    Long userId = nextUserId();
    UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
    AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "summarized-session");
    summaryRepository.save(Summary.createCompleted(userBook.getId(), session.getId(), "제목", "본문", "인용"));

    Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
            .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

    assertThat(slice.getContent()).hasSize(1);
    assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZED");
}

@Test
@DisplayName("findSessionsByUserBookIdAndOwner: ACTIVE + 최신 감상문 FAILED 면 'FAILED' 로 도출")
void status_FAILED_도출() {
    Long userId = nextUserId();
    UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
    AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "failed-session");
    summaryRepository.save(Summary.createFailed(userBook.getId(), session.getId()));

    Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
            .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

    assertThat(slice.getContent()).hasSize(1);
    assertThat(slice.getContent().get(0).status()).isEqualTo("FAILED");
}

@Test
@DisplayName("findSessionsByUserBookIdAndOwner: 감상문 여러 건이면 최신 행 기준으로 도출된다 (FAILED 후 COMPLETED → SUMMARIZED)")
void status_최신_감상문_기준_도출() {
    Long userId = nextUserId();
    UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
    AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "retried-session");
    summaryRepository.save(Summary.createFailed(userBook.getId(), session.getId()));
    summaryRepository.save(Summary.createCompleted(userBook.getId(), session.getId(), "제목", "본문", "인용"));

    Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
            .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

    assertThat(slice.getContent()).hasSize(1);
    assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZED");
    // 행이 2건이어도 최신 한 건만 join 되어 세션은 한 번만 나타난다.
}

@Test
@DisplayName("findSessionsByUserBookIdAndOwner: 재생성 중(LOCKED)이면 직전 COMPLETED 가 있어도 'SUMMARIZING'")
void status_재생성_중에는_SUMMARIZING_우선() {
    Long userId = nextUserId();
    UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
    AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.LOCKED, "regenerating-session");
    summaryRepository.save(Summary.createCompleted(userBook.getId(), session.getId(), "제목", "본문", "인용"));

    Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
            .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

    assertThat(slice.getContent()).hasSize(1);
    assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZING");
}
```

(`status_CLOSED_without_summary` 테스트는 삭제 — "잠기지 않았는데 감상문도 없는 CLOSED" 라는 상태가 새 모델에 존재하지 않는다. LOCKED + 감상문 없음 = 첫 생성 중 = 위 `status_SUMMARIZING_도출` 이 대체.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.readum.model.aiChat.repository.AiChatSessionRepositoryTest"`
Expected: FAIL — 표시 상태가 'SUMMARIZED' 가 아니라 'CLOSED' 로 나오고, 감상문 2건 세션이 중복 행으로 나옴

- [ ] **Step 3: JPQL 재작성**

`AiChatSessionRepository.java` 의 `findSessionsByUserBookIdAndOwner` default 메서드와 `@Query` 를 다음으로 교체 (javadoc 의 status 도출 절도 함께 갱신):

```java
/**
 * 특정 userBook 의 채팅 세션 목록 페이지 조회.
 *
 * 정렬 / lastChattedAt: 마지막으로 노출 가능한 메시지 (status COMPLETED) 의
 * createdAt 을 max() 서브쿼리로 구해 사용한다. 메시지가 아직 없는 세션은 session.createdAt 으로 fallback.
 * AiChatSession.updatedAt 을 쓰지 않는 이유 — lock()/unlock()/updateTitle() 같은 비-채팅 이벤트가
 * 갱신해 "최근 채팅 시각" 의 의미가 흐려지기 때문.
 *
 * status 도출: AiChatSession.status (ACTIVE/LOCKED) 와 세션의 최신 Summary.status (COMPLETED/FAILED) 를
 * CASE 로 합성해 단일 문자열로 반환한다. 매핑 규칙:
 *  - session LOCKED                          → "SUMMARIZING" (감상문 생성 중 — 직전 감상문 존재 여부와 무관)
 *  - session ACTIVE + 감상문 없음             → "ACTIVE"
 *  - session ACTIVE + 최신 감상문 FAILED      → "FAILED"
 *  - session ACTIVE + 최신 감상문 COMPLETED   → "SUMMARIZED"
 *
 * 감상문은 세션당 여러 건(재생성 이력) 존재할 수 있으므로 left join 의 on 절에서
 * max(id) 서브쿼리로 최신 한 건만 매칭한다.
 * Summary 와 AiChatSession 사이에 JPA 연관관계가 없어 left join 의 on 절로 직접 매칭한다.
 * Hibernate 6+ 의 entity-without-association join 문법.
 *
 * 호출자 시그니처를 단순하게 유지하기 위해 default 메서드로 감싸고, 내부 @Query 에 enum 파라미터를 바인딩한다.
 */
default Slice<AiChatSessionListProjection> findSessionsByUserBookIdAndOwner(
        Long userBookId, Long userId, Pageable pageable
) {
    return findSessionsByUserBookIdAndOwnerInternal(
            userBookId,
            userId,
            AiChatSession.Status.LOCKED,
            Summary.Status.FAILED,
            Summary.Status.COMPLETED,
            AiChatMessage.Status.COMPLETED,
            pageable
    );
}

@Query("""
        select new com.readum.model.aiChat.repository.projection.AiChatSessionListProjection(
                   aiChatSession.id
                 , aiChatSession.title
                 , case
                       when aiChatSession.status = :lockedStatus then 'SUMMARIZING'
                       when summary.status = :summaryFailedStatus then 'FAILED'
                       when summary.status = :summaryCompletedStatus then 'SUMMARIZED'
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
          left join Summary summary
                 on summary.aiChatSessionId = aiChatSession.id
                and summary.id = (select max(otherSummary.id)
                                    from Summary otherSummary
                                   where otherSummary.aiChatSessionId = aiChatSession.id)
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
        @Param("summaryFailedStatus") Summary.Status summaryFailedStatus,
        @Param("summaryCompletedStatus") Summary.Status summaryCompletedStatus,
        @Param("messageCompletedStatus") AiChatMessage.Status messageCompletedStatus,
        Pageable pageable
);
```

- [ ] **Step 4: enum 과 projection 주석 갱신**

`AiChatSessionDisplayStatus.java` 전체:

```java
package com.readum.domain.aiChat.dto;

/**
 * 책별 세션 목록 응답에 노출되는 합성 상태.
 * AiChatSession.Status (ACTIVE/LOCKED) 와 세션의 최신 Summary.Status (COMPLETED/FAILED) 를
 * Repository JPQL CASE 식이 합성한 결과를 enum 으로 받아 컴파일 타임 안전성을 확보한다.
 *  - SUMMARIZING: 감상문 생성 중 (세션 잠김 — 메시지 전송 불가)
 *  - ACTIVE: 대화 가능, 생성된 감상문 없음
 *  - SUMMARIZED: 대화 가능, 최신 감상문 생성 완료
 *  - FAILED: 대화 가능, 최신 감상문 생성 실패
 */
public enum AiChatSessionDisplayStatus {
    ACTIVE,
    SUMMARIZING,
    SUMMARIZED,
    FAILED
}
```

`AiChatSessionListProjection.java` javadoc 의 상태 나열을 `"ACTIVE" / "SUMMARIZING" / "SUMMARIZED" / "FAILED"` 로 수정.

- [ ] **Step 4-1: 표시 상태 문자열을 쓰는 다른 테스트 갱신**

| 파일:행 | 변경 내용 |
|---|---|
| `AiChatSessionSearchServiceTest.java` 114행 | `new AiChatSessionListProjection(2L, "종료", "CLOSED", base.minusDays(1))` → `new AiChatSessionListProjection(2L, "감상문 완료", "SUMMARIZED", base.minusDays(1))` |
| `AiChatSessionSearchServiceTest.java` 128-131행 부근 | 기대값 나열 중 `AiChatSessionDisplayStatus.CLOSED` → `AiChatSessionDisplayStatus.SUMMARIZED` |
| `AiChatControllerTest.java` 165-168행 부근 | `AiChatSessionResult` 생성 인자 중 `AiChatSessionDisplayStatus.CLOSED` → `AiChatSessionDisplayStatus.SUMMARIZED` (라벨 문자열 "종료" 류가 있으면 "감상문 완료" 로) |
| `AiChatControllerTest.java` 189행 | `jsonPath("$.data.sessions[2].status").value("CLOSED")` → `.value("SUMMARIZED")` |

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.model.aiChat.repository.AiChatSessionRepositoryTest"`
Expected: PASS

- [ ] **Step 6: 전체 테스트 확인 후 Commit**

Run: `./gradlew test`
Expected: PASS

```bash
git add -A src
git commit -m "feat(ai-chat): 세션 목록 표시 상태를 SUMMARIZED 체계로 전환하고 최신 감상문만 join"
```

---

### Task 6: SummaryDraftService 새 트랜잭션 구조

TX1 은 "검증 + 세션 잠금" 만 수행하고 감상문 행을 미리 만들지 않는다. 비동기 생성이 끝나면 성공/실패 각각 "결과 행 1건 기록 + 세션 잠금 해제" 를 한 트랜잭션으로 수행한다.

**Files:**
- Modify: `src/main/java/com/readum/domain/aiChat/service/SummaryDraftService.java`
- Modify: `src/test/java/com/readum/domain/aiChat/service/SummaryDraftServiceTest.java`

- [ ] **Step 1: 테스트를 새 계약으로 재작성 (TDD)**

`SummaryDraftServiceTest.java` 에서 기존 `정상_요청시_세션을_닫고_Summary를_COMPLETED로_저장한다` / `AI_호출_실패시_Summary가_FAILED로_마킹된다` 두 테스트를 다음 세 개로 교체한다 (다른 테스트는 유지). `import org.mockito.ArgumentCaptor;` 추가, 더 이상 안 쓰는 `SummaryFixture` import 제거:

```java
@Test
void 정상_요청시_COMPLETED_감상문을_새_행으로_저장하고_세션을_다시_활성화한다() {
    AiChatSession session = activeSession(SUFFICIENT_TOKENS);
    List<AiChatMessage> messages = List.of(
            userMessage(SESSION_ID, "이 책에서 가장 인상 깊은 장면은?"),
            assistantMessage(SESSION_ID, "주인공이 선택의 기로에 서는 장면이 인상적입니다.")
    );
    SummaryDraftResult expected = new SummaryDraftResult("나의 독서 감상", "깊은 울림을 주는 책이었다.", "선택의 기로에서");

    given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
    given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
    given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(messages);
    given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
    given(aiSummaryClient.generate(messages)).willReturn(expected);

    summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

    ArgumentCaptor<Summary> summaryCaptor = ArgumentCaptor.forClass(Summary.class);
    verify(summaryRepository).save(summaryCaptor.capture());
    Summary saved = summaryCaptor.getValue();
    assertThat(saved.getStatus()).isEqualTo(Summary.Status.COMPLETED);
    assertThat(saved.getAiChatSessionId()).isEqualTo(SESSION_ID);
    assertThat(saved.getTitle()).isEqualTo("나의 독서 감상");
    assertThat(saved.getBody()).isEqualTo("깊은 울림을 주는 책이었다.");
    assertThat(saved.getQuote()).isEqualTo("선택의 기로에서");
    assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
}

@Test
void 생성이_진행되는_동안에는_세션이_잠겨있다() {
    AiChatSession session = activeSession(SUFFICIENT_TOKENS);
    SummaryDraftResult expected = new SummaryDraftResult("제목", "본문", "인용");

    given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
    given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
    given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(List.of());
    given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
    // LLM 호출 시점(생성 진행 중)에 세션이 LOCKED 인 것을 검증한다.
    given(aiSummaryClient.generate(any())).willAnswer(invocation -> {
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.LOCKED);
        return expected;
    });

    summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

    assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
}

@Test
void AI_호출_실패시_FAILED_감상문을_새_행으로_저장하고_세션을_다시_활성화한다() {
    AiChatSession session = activeSession(SUFFICIENT_TOKENS);

    given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
    given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
    given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(List.of());
    given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
    given(aiSummaryClient.generate(any())).willThrow(new RuntimeException("AI 오류"));

    summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

    ArgumentCaptor<Summary> summaryCaptor = ArgumentCaptor.forClass(Summary.class);
    verify(summaryRepository).save(summaryCaptor.capture());
    Summary saved = summaryCaptor.getValue();
    assertThat(saved.getStatus()).isEqualTo(Summary.Status.FAILED);
    assertThat(saved.getAiChatSessionId()).isEqualTo(SESSION_ID);
    assertThat(saved.getTitle()).isNull();
    assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
}
```

(상수 `SUMMARY_ID` 는 더 이상 쓰이지 않으면 삭제한다.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.SummaryDraftServiceTest"`
Expected: FAIL — 기존 구현은 IN_PROGRESS 행을 미리 만들고 세션을 다시 활성화하지 않음

- [ ] **Step 3: SummaryDraftService 재작성**

`execute` / `generateAsync` / `PreparedContext` 를 다음으로 교체 (필드·생성자는 그대로):

```java
/**
 * TX1(검증 + 세션 잠금)을 동기로 완료한 뒤 즉시 반환한다.
 * LLM 호출과 결과 기록(TX2)은 @Async 메서드에서 백그라운드로 처리된다.
 * 감상문 행은 미리 만들지 않는다 — "생성 중" 은 세션 LOCKED 상태가 표현하고,
 * 감상문(Summary) 은 생성 시도가 끝난 시점에 결과(COMPLETED/FAILED)와 함께 한 번만 기록된다.
 * 중복 생성 방지: 비관적 락 + "ACTIVE 일 때만 LOCKED 전이"(SummaryDraftPolicy) 조합이 막는다.
 */
public void execute(Long sessionId, String userSessionId) {
    User user = userRepository.findBySessionId(userSessionId)
            .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

    // TX1: 검증 + 세션 잠금 — 커밋 후 즉시 반환
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

    // 백그라운드에서 LLM 호출 + 결과 기록
    generateAsync(sessionId, preparedContext);
}

/**
 * TX 밖에서 LLM 을 호출하고 결과를 DB 에 반영한다.
 * @Async 로 별도 스레드에서 실행되므로 호출자는 즉시 반환된다.
 *
 * 성공/실패 모두 "감상문 행 기록 + 세션 잠금 해제(unlock)" 를 한 트랜잭션으로 묶는다 —
 * 세션은 다시 활성화됐는데 결과 행이 없는 어중간한 상태를 막기 위함.
 * 알려진 한계: 생성 도중 프로세스가 죽으면 세션이 LOCKED 로 남는다 (복구 정책은 별도 이슈).
 */
@Async
public void generateAsync(Long sessionId, PreparedContext preparedContext) {
    SummaryDraftResult result;
    try {
        result = aiSummaryClient.generate(preparedContext.messages());
    } catch (Exception e) {
        // TX2 (실패 경로): FAILED 행 기록 + 세션 잠금 해제
        transactionTemplate.executeWithoutResult(status -> {
            summaryRepository.save(Summary.createFailed(preparedContext.userBookId(), sessionId));
            aiChatSessionRepository.findById(sessionId).ifPresent(AiChatSession::unlock);
        });
        log.error("감상문 생성 실패 sessionId={}", sessionId, e);
        return;
    }

    // TX2 (성공 경로): COMPLETED 행 기록 + 세션 잠금 해제
    transactionTemplate.executeWithoutResult(status -> {
        summaryRepository.save(Summary.createCompleted(
                preparedContext.userBookId(), sessionId,
                result.title(), result.body(), result.quote()));
        aiChatSessionRepository.findById(sessionId).ifPresent(AiChatSession::unlock);
    });
}

public record PreparedContext(Long userBookId, List<AiChatMessage> messages) {}
```

클래스 상단 javadoc 의 `TX1(검증 + IN_PROGRESS 저장)` 문구도 위 새 설명에 맞게 정리한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.SummaryDraftServiceTest"`
Expected: PASS

- [ ] **Step 5: 전체 테스트 확인 후 Commit**

Run: `./gradlew test`
Expected: PASS

```bash
git add -A src
git commit -m "feat(ai-chat): 감상문 생성을 세션 잠금-해제 구조로 전환하고 결과를 새 행으로 기록"
```

---

### Task 7: SummarySearchService 새 판정 + 신규 단위 테스트

GET /summary 판정을 "세션 LOCKED → 생성 중 409, 아니면 최신 행 기준" 으로 바꾼다. 이 service 는 기존에 단위 테스트가 없었으므로 새로 만든다.

**Files:**
- Modify: `src/main/java/com/readum/domain/aiChat/service/SummarySearchService.java`
- Create: `src/test/java/com/readum/domain/aiChat/service/SummarySearchServiceTest.java`

- [ ] **Step 1: 신규 단위 테스트 작성 (TDD)**

```java
package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.SummaryFixture;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummarySearchServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long SUMMARY_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final String USER_SESSION_ID = "test-session-id";
    private static final Long USER_BOOK_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @InjectMocks
    private SummarySearchService summarySearchService;

    @BeforeEach
    void setUp() {
        User testUser = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
    }

    @Test
    void 최신_감상문이_COMPLETED면_내용을_반환한다() {
        givenOwnedSession(activeSession());
        given(summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(SESSION_ID))
                .willReturn(Optional.of(SummaryFixture.persistedCompletedSummary(
                        SUMMARY_ID, USER_BOOK_ID, SESSION_ID, "제목", "본문", "인용")));

        SummaryResult result = summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID);

        assertThat(result.title()).isEqualTo("제목");
        assertThat(result.body()).isEqualTo("본문");
        assertThat(result.quote()).isEqualTo("인용");
    }

    @Test
    void 세션이_잠겨있으면_생성_중_ConflictException이_발생한다() {
        givenOwnedSession(lockedSession());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS);

        // 생성 중에는 감상문 조회 자체를 하지 않는다 (직전 감상문이 있어도 노출하지 않음 — 폴링 계약 유지)
        verify(summaryRepository, never()).findTopByAiChatSessionIdOrderByIdDesc(any());
    }

    @Test
    void 최신_감상문이_FAILED면_생성_실패_ConflictException이_발생한다() {
        givenOwnedSession(activeSession());
        given(summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(SESSION_ID))
                .willReturn(Optional.of(SummaryFixture.persistedFailedSummary(
                        SUMMARY_ID, USER_BOOK_ID, SESSION_ID)));

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_GENERATION_FAILED);
    }

    @Test
    void 감상문이_한_번도_생성되지_않았으면_NotFoundException이_발생한다() {
        givenOwnedSession(activeSession());
        given(summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(SESSION_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_NOT_FOUND);
    }

    @Test
    void 세션이_없거나_소유자가_아니면_NotFoundException이_발생한다() {
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    private void givenOwnedSession(AiChatSession session) {
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(session));
    }

    private AiChatSession activeSession() {
        return AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 5, 600, "제목");
    }

    private AiChatSession lockedSession() {
        return AiChatSessionFixture.persistedLockedSession(SESSION_ID, USER_BOOK_ID, 5, 600, "제목");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.readum.domain.aiChat.service.SummarySearchServiceTest"`
Expected: FAIL — 기존 구현은 세션 잠김을 보지 않고 `findByAiChatSessionId` 를 사용

- [ ] **Step 3: SummarySearchService 재작성**

`findBySessionId` 를 다음으로 교체 (`import com.readum.model.aiChat.entity.AiChatSession;` 추가):

```java
public SummaryResult findBySessionId(Long sessionId, String userSessionId) {
    User user = userRepository.findBySessionId(userSessionId)
            .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

    AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, user.getId())
            .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

    // "생성 중" 은 감상문 행이 아니라 세션 잠금 상태가 표현한다.
    // 재생성 중에는 직전 감상문이 있어도 409 를 반환해 폴링 계약(생성 요청 → 폴링 → 완료/실패)을 유지한다.
    if (session.isLocked()) {
        throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
    }

    Summary summary = summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(sessionId)
            .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SUMMARY_NOT_FOUND));

    return switch (summary.getStatus()) {
        case COMPLETED -> SummaryResult.from(summary);
        case FAILED -> throw new ConflictException(AiChatErrorCode.SUMMARY_GENERATION_FAILED);
        // 과도기 분기: Status.IN_PROGRESS 는 Task 8 에서 enum 과 함께 제거된다. 새 코드는 이 행을 만들지 않는다.
        case IN_PROGRESS -> throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
    };
}
```

- [ ] **Step 4: 테스트 통과 확인 후 Commit**

Run: `./gradlew test`
Expected: PASS

```bash
git add -A src
git commit -m "feat(ai-chat): 감상문 조회를 세션 잠금과 최신 행 기준으로 판정"
```

---

### Task 8: IN_PROGRESS 잔재 제거 — Summary write-once 완성

이제 `Summary.Status.IN_PROGRESS` 를 참조하는 곳이 없으므로 enum 값과 구 API 를 제거해 "감상문 행은 불변" 을 타입으로 강제한다.

**Files:**
- Modify: `src/main/java/com/readum/model/summary/entity/Summary.java`
- Modify: `src/main/java/com/readum/model/summary/repository/SummaryRepository.java`
- Modify: `src/main/java/com/readum/domain/aiChat/service/SummarySearchService.java`
- Modify: `src/test/java/com/readum/model/summary/entity/SummaryFixture.java`

- [ ] **Step 1: Summary 엔티티 정리**

- enum 을 `{ COMPLETED, FAILED }` 로 축소
- `createInProgress()`, `complete()`, `fail()`, `isCompleted()` 메서드 삭제 (write-once — 생성 후 상태·내용 변경 경로 자체를 없앤다. `isCompleted` 는 호출처 없음)
- (unique 제약 어노테이션 제거는 Task 4 로 앞당겨져 이미 완료됨)
- 클래스에 다음 javadoc 추가:

```java
/**
 * 감상문 — 끝난 생성 시도의 불변 기록.
 * 생성이 끝난 시점에 결과(COMPLETED/FAILED)와 함께 한 번만 만들어지고 이후 변경되지 않는다.
 * 세션과 1:N — 재생성할 때마다 행이 추가되며 "현재 감상문" 은 최신 행이다 (과거 행은 이력/품질 감사용).
 * "생성 중" 상태는 이 엔티티가 아니라 세션(AiChatSession.Status.LOCKED) 이 표현한다.
 */
```

- [ ] **Step 2: SummarySearchService 의 과도기 분기 제거**

Task 7 Step 3 에서 추가한 `case IN_PROGRESS -> ...` 행을 삭제한다.

- [ ] **Step 3: SummaryRepository.findByAiChatSessionId 삭제**

호출처가 없고, 세션당 복수 행 환경에서 단건 derived query 는 `IncorrectResultSizeDataAccessException` 을 던지는 함정이 되므로 제거한다.

- [ ] **Step 4: SummaryFixture 의 persistedInProgressSummary 삭제**

클래스 javadoc 의 "(생성 중)" 표현도 "(생성 완료/실패)" 로 수정.

- [ ] **Step 5: 잔재 없는지 확인**

Run: `grep -rn "Status.IN_PROGRESS\|createInProgress\|persistedInProgressSummary\|findByAiChatSessionId(" src --include="*.java"`
Expected: 출력 없음 (`SUMMARY_IN_PROGRESS` 에러 코드는 별개 식별자라 위 패턴에 걸리지 않음)

- [ ] **Step 6: 전체 테스트 통과 확인 후 Commit**

Run: `./gradlew test`
Expected: PASS

```bash
git add -A src
git commit -m "refactor(ai-chat): 감상문 IN_PROGRESS 상태 제거로 write-once 기록 완성"
```

---

### Task 9: Swagger 문서 갱신

**Files:**
- Modify: `src/main/java/com/readum/presentation/controller/aiChat/AiChatController.java`

- [ ] **Step 1: 세션 목록 조회 (80-94행 부근)**

description 의 상태 설명 문장을 다음으로 교체:

```java
description = "선택한 도서(userBookId)에 대한 채팅 세션을 최근 채팅 날짜 내림차순으로 페이지네이션 조회한다. " +
        "각 세션은 ACTIVE / SUMMARIZING / SUMMARIZED / FAILED 상태로 구분된다 — " +
        "SUMMARIZING 은 감상문 비동기 생성 중(메시지 전송 불가), SUMMARIZED 는 최신 감상문 생성 완료, " +
        "FAILED 는 최신 감상문 생성 실패를 의미한다. SUMMARIZING 을 제외한 모든 세션은 대화를 이어갈 수 있다. " +
        "lastChattedDate 는 마지막으로 노출된 메시지(USER/ASSISTANT, COMPLETED) 의 날짜이며, " +
        "메시지가 없는 세션은 세션 생성 날짜로 fallback 된다. " +
        "세션이 없으면 빈 배열로 200 응답한다."
```

- [ ] **Step 2: 메시지 전송 (141행 부근)**

```java
// 변경 전
@ApiResponse(responseCode = "400", description = "본문 검증 실패 / 종료된 세션"),
// 변경 후
@ApiResponse(responseCode = "400", description = "본문 검증 실패 / 감상문 생성 중인 세션(SESSION_LOCKED)"),
```

- [ ] **Step 3: 감상문 조회 (208-224행 부근)**

`@Operation` description 을 다음으로 교체:

```java
description = """
        세션의 최신 감상문(제목·본문·인상 깊은 구절)을 조회한다.

        응답 분기:
        - 세션이 감상문 생성 중(잠김): 409 SUMMARY_IN_PROGRESS — 잠시 후 재시도 필요
        - 최신 감상문 COMPLETED: 200 — 감상문 정상 반환
        - 최신 감상문 FAILED: 409 SUMMARY_GENERATION_FAILED — AI 생성 실패
        - 감상문 생성 이력 없음: 404

        감상문 생성이 끝나면(성공/실패 무관) 세션은 자동으로 다시 활성화되어 대화를 이어갈 수 있다.
        같은 세션에서 감상문을 다시 생성하면 새 감상문이 최신으로 조회된다 (과거 감상문은 DB 에 이력으로 보존).
        """
```

`@ApiResponses` 의 409 를:

```java
@ApiResponse(responseCode = "409", description = "감상문 생성 중 또는 최신 감상문이 생성 실패 상태")
```

- [ ] **Step 4: eligibility (235-239행 부근)**

```java
// 변경 전
description = "AI 채팅 세션이 감상문 초안 생성 조건(미종료 + 누적 토큰 충족)을 만족하는지 검사한다. " +
// 변경 후
description = "AI 채팅 세션이 감상문 초안 생성 조건(생성 진행 중이 아님 + 누적 토큰 충족)을 만족하는지 검사한다. " +
```

- [ ] **Step 5: 감상문 초안 생성 요청 (255-270행 부근)**

`@Operation` description 을 다음으로 교체:

```java
description = """
        AI 채팅 세션의 대화 내용을 바탕으로 감상문 초안 생성을 요청한다.
        세션 검증 후 세션을 잠그고(생성 중 메시지 전송 차단) 즉시 202를 반환하며, 실제 생성은 백그라운드에서 진행된다.
        생성 결과는 GET /sessions/{sessionId}/summary 로 폴링하여 확인한다.
        생성이 끝나면(성공/실패 무관) 세션은 자동으로 다시 활성화되어 대화를 이어갈 수 있다.
        이미 감상문이 있는 세션도 다시 요청할 수 있다 — 새 감상문이 새 행으로 추가되고 최신 행이 현재 감상문이 된다.
        누적 토큰이 임계값에 미치지 못하면 422 를 반환한다.
        """
```

409 description 을:

```java
@ApiResponse(responseCode = "409", description = "감상문 생성이 이미 진행 중인 세션"),
```

- [ ] **Step 6: 빌드 확인 후 Commit**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

```bash
git add -A src
git commit -m "docs(ai-chat): 감상문 재생성/세션 재활성화 동작에 맞게 swagger 설명 갱신"
```

---

### Task 10: 배포 시 데이터 마이그레이션 SQL

**Files:**
- Create: `docs/sql/2026-06-12-issue-69-on-deploy.sql`

- [ ] **Step 1: SQL 파일 작성**

```sql
-- 이슈 #69: 감상문 생성 완료 후 대화 이어가기 — 배포 시 데이터 마이그레이션
-- 적용 시점: 새 코드가 dev 에 머지·배포된 직후 (배포와 한 묶음으로 실행)
-- 적용 대상: dev RDS (readum)
--
-- 새 코드는 세션 상태를 ACTIVE/LOCKED 로 읽는다. 구 모델의 'CLOSED' 값이 남아 있으면
-- 해당 세션 조회 시 enum 매핑 오류가 나므로, 배포 직후 반드시 실행해야 한다.

-- 1) 구 모델이 남긴 IN_PROGRESS 감상문 → FAILED
--    (생성 도중 프로세스가 죽어 영구 고착된 행. 새 모델에는 IN_PROGRESS 상태가 없다)
UPDATE summary
   SET status = 'FAILED'
     , updated_at = NOW()
 WHERE status = 'IN_PROGRESS';

-- 2) 구 모델의 '종료된' 세션 → 전부 ACTIVE
--    (새 모델에서 감상문 생성이 끝난 세션은 대화 가능 세션이다)
UPDATE ai_chat_session
   SET status = 'ACTIVE'
     , updated_at = NOW()
 WHERE status = 'CLOSED';

-- 검증: 두 쿼리 모두 0 이어야 한다.
-- SELECT COUNT(*) FROM summary WHERE status = 'IN_PROGRESS';
-- SELECT COUNT(*) FROM ai_chat_session WHERE status NOT IN ('ACTIVE', 'LOCKED');
```

- [ ] **Step 2: Commit**

```bash
git add docs/sql/2026-06-12-issue-69-on-deploy.sql
git commit -m "chore(ai-chat): 세션 상태/감상문 데이터 배포 마이그레이션 SQL 추가"
```

- [ ] **Step 3: 사용자에게 적용 시점 안내**

이 SQL 은 **지금 실행하지 않는다.** PR 머지 → dev 배포 직후에 실행해야 함을 사용자에게 명확히 전달한다 (배포 전 실행 시 구 코드가 '되살아난' 세션에 구 모델 동작을 적용하고, 미실행 시 새 코드가 'CLOSED' 값을 읽다 오류).

---

### Task 11: 전체 검증 및 마무리

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, 전체 테스트 PASS

- [ ] **Step 2: 스펙 대비 누락 점검**

스펙(`docs/superpowers/specs/2026-06-12-resume-summarized-session-design.md`)의 테스트 계획 항목과 실제 테스트를 대조:
- 생성 성공/실패 후 세션 ACTIVE 복귀 → `SummaryDraftServiceTest` (Task 6)
- 복귀한 세션에 메시지 전송 → 세션이 ACTIVE 면 `loadHistory` 가 통과 (LOCKED 차단 테스트의 대우) — `AiChatMessagePersistServiceTest` 기존 정상 경로 테스트가 커버
- LOCKED 중 메시지/재생성 차단 → Task 2 / Task 3 테스트
- 재생성 시 새 행 추가 + 기존 행 보존 → `SummaryRepositoryTest` (Task 4)
- 목록 표시 상태 4종 + 최신 행 기준 + 재생성 중 SUMMARIZING 우선 → `AiChatSessionRepositoryTest` (Task 5)
- GET /summary 분기 4종 → `SummarySearchServiceTest` (Task 7)

- [ ] **Step 3: 마무리**

superpowers:finishing-a-development-branch 스킬로 진행 — PR 생성 시 본문에 closes #69, 프론트 조율 사항(표시 상태 `SUMMARIZED`, eligibility reason 변경), 배포 시 `docs/sql/2026-06-12-issue-69-on-deploy.sql` 실행 필요를 명시한다.
