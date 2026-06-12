package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findByAiChatSessionId(Long aiChatSessionId);

    List<Summary> findByUserBookIdInAndStatusAndSummaryDateBetweenOrderBySummaryDateDescCreatedAtDescIdDesc(
            Collection<Long> userBookIds, Summary.Status status, LocalDate startDate, LocalDate endDate);

    default List<Summary> findMonthlyCompleted(
            Collection<Long> userBookIds, LocalDate startDate, LocalDate endDate) {
        return findByUserBookIdInAndStatusAndSummaryDateBetweenOrderBySummaryDateDescCreatedAtDescIdDesc(
                userBookIds, Summary.Status.COMPLETED, startDate, endDate);
    }
}
