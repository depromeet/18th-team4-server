package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SummarySearchService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final SummaryRepository summaryRepository;

    public SummaryResult findBySessionId(Long sessionId, Long userId) {
        aiChatSessionRepository.findByIdAndOwner(sessionId, userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        Summary summary = summaryRepository.findByAiChatSessionId(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SUMMARY_NOT_FOUND));

        return switch (summary.getStatus()) {
            case COMPLETED -> SummaryResult.from(summary);
            case IN_PROGRESS -> throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
            case FAILED -> throw new ConflictException(AiChatErrorCode.SUMMARY_GENERATION_FAILED);
        };
    }
}
