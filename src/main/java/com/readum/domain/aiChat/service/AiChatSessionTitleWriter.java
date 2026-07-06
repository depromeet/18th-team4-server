package com.readum.domain.aiChat.service;

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
}
