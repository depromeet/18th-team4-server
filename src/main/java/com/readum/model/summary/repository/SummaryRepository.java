package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findByAiChatSessionId(Long aiChatSessionId);

    /**
     * 세션의 최신 감상문 한 건. 감상문은 세션당 여러 건(재생성 이력) 존재할 수 있고,
     * "현재 감상문" 은 항상 가장 최근 행이다.
     */
    Optional<Summary> findTopByAiChatSessionIdOrderByIdDesc(Long aiChatSessionId);
}
