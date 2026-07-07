package com.readum.domain.aiChat.service;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 제목 갱신의 DB 쓰기 구간만 트랜잭션으로 묶는 헬퍼.
 * LLM 호출이 트랜잭션(= DB 커넥션 점유) 안에 들어오지 않도록
 * AiChatSessionTitleService 에서 분리했다. findById + updateTitle 을
 * 한 트랜잭션으로 묶어 dirty-check flush 를 보장한다.
 */
@Component
@RequiredArgsConstructor
class AiChatSessionTitleWriter {

    private final AiChatSessionRepository aiChatSessionRepository;

    @Transactional
    public void updateTitle(Long sessionId, String title) {
        aiChatSessionRepository.findById(sessionId)
                .ifPresent(session -> session.updateTitle(title));
    }

    /**
     * 제목 생성이 실패했을 때만 호출하는 대체 경로.
     * 아직 제목이 없는 세션에만 기본 제목을 넣는다. 이미 제목이 있으면(예: 향후 재생성 중 실패)
     * 성공했던 제목을 덮어쓰지 않도록 그대로 둔다.
     */
    @Transactional
    public void applyDefaultTitleIfAbsent(Long sessionId) {
        aiChatSessionRepository.findById(sessionId)
                .filter(session -> !session.hasTitle())
                .ifPresent(session -> session.updateTitle(AiChatSession.DEFAULT_TITLE));
    }
}
