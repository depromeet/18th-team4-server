package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findByAiChatSessionId(Long aiChatSessionId);

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
}
