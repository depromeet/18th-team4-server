package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SummaryDraftService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiSummaryClient aiSummaryClient;
    private final UserBookRepository userBookRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;

    @Transactional
    public SummaryDraftResult execute(Long sessionId, Long userId) {
        AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        userBookRepository.findByIdAndUserId(session.getUserBookId(), userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        summaryDraftPolicy.assertEligible(session);

        List<AiChatMessage> messages =
                aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);

        SummaryDraftResult result = aiSummaryClient.generate(messages);

        session.close();

        return result;
    }
}
