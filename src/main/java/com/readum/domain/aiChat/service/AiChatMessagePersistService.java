package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.history.ChatHistoryBuilder;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 채팅 메시지의 영속화 책임을 담당하는 service.
 * Command/Query service 형태가 아니라 영속화 책임만 모아둔 service 인 이유:
 * AiChatMessageSendService 의 Reactor 콜백 안에서 @Transactional 메서드를
 * "외부 호출"(프록시 통과) 로 만들기 위해 별도 service 로 분리했다 (self-invocation 회피).
 */
@Service
@RequiredArgsConstructor
public class AiChatMessagePersistService {

    // last_message_preview 컬럼이 VARCHAR(500) 이라 그에 맞춰 자른다 (DB 스키마 결합).
    private static final int LAST_MESSAGE_PREVIEW_MAX_LENGTH = 500;

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final ChatHistoryBuilder chatHistoryBuilder;

    /**
     * 세션 소유권 및 종료 여부 검증, 이전 이력 조회, USER 메시지 INSERT 까지를
     * 단일 트랜잭션으로 처리한다.
     * 호출 순서 주의: 이전 이력 조회를 USER 메시지 save 전에 수행한다.
     * Hibernate auto-flush 로 인해 save 후에 조회하면 방금 저장한 USER 메시지가
     * 결과에 포함되어 LLM 컨텍스트에 중복으로 들어가게 된다.
     */
    @Transactional
    public List<HistoryMessage> loadHistoryAndRecordUserMessage(
            Long sessionId, Long userId, String normalizedContent
    ) {
        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
        if (session.isClosed()) {
            throw new BadRequestException(AiChatErrorCode.SESSION_CLOSED);
        }
        List<HistoryMessage> previousHistory = chatHistoryBuilder.buildPreviousHistory(sessionId);
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, normalizedContent));
        session.appendUserMessage(buildPreview(normalizedContent));
        return previousHistory;
    }

    @Transactional
    public AiChatMessage saveAssistantSuccess(
            Long sessionId, String accumulated, AiChatChunk.Completion meta
    ) {
        Integer inputTokens = meta == null ? null : meta.inputTokens();
        Integer outputTokens = meta == null ? null : meta.outputTokens();
        Integer totalTokens = meta == null ? null : meta.totalTokens();

        AiChatMessage saved = aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(
                sessionId, accumulated, inputTokens, outputTokens, totalTokens
        ));
        if (totalTokens != null && totalTokens > 0) {
            aiChatSessionRepository.findById(sessionId)
                    .ifPresent(session -> session.addAssistantTokens(totalTokens));
        }
        return saved;
    }

    @Transactional
    public void saveAssistantFailed(
            Long sessionId, String partial, AiChatChunk.Completion meta
    ) {
        Integer inputTokens = meta == null ? null : meta.inputTokens();
        Integer outputTokens = meta == null ? null : meta.outputTokens();
        Integer totalTokens = meta == null ? null : meta.totalTokens();

        aiChatMessageRepository.save(AiChatMessage.createAssistantFailed(
                sessionId, partial == null ? "" : partial, inputTokens, outputTokens, totalTokens
        ));
        if (totalTokens != null && totalTokens > 0) {
            aiChatSessionRepository.findById(sessionId)
                    .ifPresent(session -> session.addAssistantTokens(totalTokens));
        }
    }

    private String buildPreview(String content) {
        return content.length() <= LAST_MESSAGE_PREVIEW_MAX_LENGTH
                ? content
                : content.substring(0, LAST_MESSAGE_PREVIEW_MAX_LENGTH);
    }
}
