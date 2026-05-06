package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findByAiChatSessionId(Long aiChatSessionId);
}
