package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * 월별 달력용 — 사용자의 등록 도서들에 속한 감상문을 생성일(createdAt) 기간으로 좁혀
     * 최신순(createdAt DESC, id DESC) 으로 반환한다.
     * 감상문은 성공 기록만 남으므로(write-once) 상태 필터가 필요 없다. 별도 summaryDate 컬럼을 두지 않고
     * "생성된 날짜" 는 createdAt 으로 본다. 기간은 [startDate 00:00, endDate+1일 00:00) 반열림으로 좁힌다.
     */
    List<Summary> findByUserBookIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            Collection<Long> userBookIds, LocalDateTime startInclusive, LocalDateTime endExclusive);

    default List<Summary> findMonthlyCompleted(
            Collection<Long> userBookIds, LocalDate startDate, LocalDate endDate) {
        return findByUserBookIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                userBookIds, startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
    }

    /**
     * 세션의 가장 최근 감상문(= 현재 감상문). 세션당 여러 건(재생성 이력)이 쌓이므로 최신 한 건을 고른다.
     * 같은 createdAt 동시 생성 대비 id 로 tiebreak.
     */
    Optional<Summary> findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(Long aiChatSessionId);

    /**
     * 여러 세션의 최신 감상문을 한 번에 — 책별 세션 목록·표시상태 합성용.
     * 세션별 max(id) 행만 추린다.
     */
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

    /**
     * 사용자 본인의 감상 기록 목록 — 세션(=책)당 가장 최근 감상문 1건만, 최신순(createdAt DESC, id DESC) Slice.
     * 세션당 여러 건(매일 자동 생성 등)이 쌓이므로 세션별 최신 행만 골라야 목록이 도배되지 않는다.
     * Summary / AiChatSession / UserBook / Book 사이에 JPA 연관관계가 없어 on 절로 직접 join 한다 (Hibernate 6+).
     * book.title 을 투영해야 하므로 UserBook/Book join 이 불가피하고, userBook.userId 필터가 소유권 검증을 겸한다.
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
