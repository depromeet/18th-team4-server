package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftEligibility;
import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;
import com.readum.domain.aiChat.dto.SummaryDraftEligibilityResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SummaryDraftSearchService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final UserBookRepository userBookRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final SummaryJobRepository summaryJobRepository;

    public SummaryDraftEligibilityResult findEligibility(Long sessionId, Long userId) {
        AiChatSession session = aiChatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        userBookRepository.findByIdAndUserId(session.getUserBookId(), userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        int progressPercent = summaryDraftPolicy.calculateProgressPercent(session.getAccumulatedTokens());

        // 이미 활성(완료·실패 전 상태 — PENDING/PROCESSING) 작업이 있으면 "생성 중" — 세션 상태와 무관하게 재요청 불가.
        SummaryDraftEligibility eligibility = summaryJobRepository.existsByActiveSessionId(sessionId)
                ? SummaryDraftEligibility.fail(IneligibleReason.SUMMARY_IN_PROGRESS)
                : summaryDraftPolicy.evaluate(session);
        return SummaryDraftEligibilityResult.from(eligibility, progressPercent);
    }
}
