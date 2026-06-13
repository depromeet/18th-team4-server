package com.readum.model.summary.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    /**
     * 등록 도서(UserBook) 삭제 cascade 용 — 그 도서에 속한 감상 기록을 일괄 삭제한다.
     * Summary 는 userBookId 를 직접 보유하므로 단순 조건으로 좁힌다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Summary summary where summary.userBookId = :userBookId")
    int deleteAllByUserBookId(@Param("userBookId") Long userBookId);

    Optional<Summary> findByAiChatSessionIdAndSummaryDate(Long aiChatSessionId, LocalDate summaryDate);

    Optional<Summary> findFirstByAiChatSessionIdOrderByCreatedAtDesc(Long aiChatSessionId);

    List<Summary> findByStatusAndRetryCountLessThan(Summary.Status status, int retryCount);

    /**
     * 월별 달력용 — 사용자의 등록 도서들에 속한 완성된 감상문을 summaryDate 기간으로 좁혀
     * 최신순(summaryDate DESC, createdAt DESC, id DESC) 으로 반환한다.
     * 조건이 모두 Summary 자기 컬럼(userBookId/status/summaryDate)이라 join 없이 derived query 로 표현한다.
     */
    List<Summary> findByUserBookIdInAndStatusAndSummaryDateBetweenOrderBySummaryDateDescCreatedAtDescIdDesc(
            Collection<Long> userBookIds, Summary.Status status, LocalDate startDate, LocalDate endDate);

    default List<Summary> findMonthlyCompleted(
            Collection<Long> userBookIds, LocalDate startDate, LocalDate endDate) {
        return findByUserBookIdInAndStatusAndSummaryDateBetweenOrderBySummaryDateDescCreatedAtDescIdDesc(
                userBookIds, Summary.Status.COMPLETED, startDate, endDate);
    }

    /**
     * 사용자 본인의 감상 기록 목록을 최신순(createdAt DESC, id DESC) 으로 Slice 조회한다.
     * 종료(CLOSED)된 세션의 완성(COMPLETED)된 감상문만 포함한다 —
     * 스케줄러가 ACTIVE 세션에 자동 생성한 COMPLETED 감상문은 sessionStatus=CLOSED 필터로 제외된다.
     *
     * 호출자 시그니처를 단순하게 유지하기 위해 default 메서드로 감싸고, 내부 @Query 에 enum 파라미터를 바인딩한다.
     */
    default Slice<SummaryHistoryProjection> findCompletedHistoryByUserId(Long userId, Pageable pageable) {
        return findCompletedHistoryByUserIdInternal(
                userId, Summary.Status.COMPLETED, AiChatSession.Status.CLOSED, pageable);
    }

    /**
     * Summary / AiChatSession / UserBook / Book 사이에 JPA 연관관계가 없어 on 절로 직접 join 한다 (Hibernate 6+).
     * 소유권 확인을 EXISTS 서브쿼리가 아니라 inner join 으로 하는 이유 — book.title 컬럼을 실제로 투영해야 하므로
     * UserBook/Book join 이 불가피하고, userBook.userId 필터가 곧 소유권 검증을 겸한다.
     */
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
               and summary.status = :summaryStatus
               and aiChatSession.status = :sessionStatus
             order by summary.createdAt desc
                    , summary.id desc
            """)
    Slice<SummaryHistoryProjection> findCompletedHistoryByUserIdInternal(
            @Param("userId") Long userId,
            @Param("summaryStatus") Summary.Status summaryStatus,
            @Param("sessionStatus") AiChatSession.Status sessionStatus,
            Pageable pageable);
}
