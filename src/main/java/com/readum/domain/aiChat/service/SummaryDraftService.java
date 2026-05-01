package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SummaryDraftService {

    // TODO: 정책 확정 후 상수값 조정 필요
    private static final int MIN_ACCUMULATED_TOKENS = 500;

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiSummaryClient aiSummaryClient;

    @Transactional
    public SummaryDraftResult execute(Long sessionId) {
        AiChatSession session = aiChatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        if (session.getStatus() == AiChatSession.Status.CLOSED) {
            throw new ConflictException(AiChatErrorCode.SESSION_ALREADY_CLOSED);
        }

        if (session.getAccumulatedTokens() < MIN_ACCUMULATED_TOKENS) {
            throw new UnprocessableEntityException(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
        }

        List<AiChatMessage> messages =
                aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);

        SummaryDraftResult result = aiSummaryClient.generate(messages);

        session.close();

        return result;
    }
}
