package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findByAiChatSessionId(Long aiChatSessionId);

    Optional<Summary> findByAiChatSessionIdAndSummaryDate(Long aiChatSessionId, LocalDate summaryDate);

    Optional<Summary> findFirstByAiChatSessionIdOrderByCreatedAtDesc(Long aiChatSessionId);

    List<Summary> findByStatusAndRetryCountLessThan(Summary.Status status, int retryCount);
}
