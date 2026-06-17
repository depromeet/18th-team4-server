package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 세션의 감상문(1:1). 종료 모델에서 세션당 최대 한 행이다. */
    Optional<Summary> findByAiChatSessionId(Long aiChatSessionId);

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
     * 사용자 본인의 감상 기록 목록 — 세션당 감상문은 1건(세션:감상문 = 1:1, uk_summary_session 제약),
     * 최신순(createdAt DESC, id DESC) Slice.
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
