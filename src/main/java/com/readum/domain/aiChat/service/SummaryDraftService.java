package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.user.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감상문 생성 요청 진입점 — 사용자가 채팅 화면에서 직접 생성을 요청하는 정식 기능.
 * 직접 생성하지 않고 작업을 적재하며, 실제 생성은 작업 큐 워커(SummaryGenerationWorker)가 처리한다.
 * 자격(ACTIVE + 누적 토큰 ≥ 문턱값, 종료 세션 제외)은 SummaryDraftPolicy 가 검증한다.
 * 중복 적재는 EnqueueSummaryJobService 의 멱등성(active_session_id unique)이 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryDraftService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final UserBookRepository userBookRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final SummaryJobRepository summaryJobRepository;
    private final EnqueueSummaryJobService enqueueSummaryJobService;

    @Transactional
    public void execute(SummaryDraftCommand command) {
        Long sessionId = command.sessionId();
        Long userId = command.userId();

        AiChatSession session = aiChatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        userBookRepository.findByIdAndUserId(session.getUserBookId(), userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        // 이미 활성(완료·실패 전 상태 — PENDING/PROCESSING) 작업이 있으면 "생성 중" — 409 로 거부(중복 요청 방지).
        if (summaryJobRepository.existsByActiveSessionId(sessionId)) {
            throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        }

        summaryDraftPolicy.assertEligible(session);

        if (!enqueueSummaryJobService.execute(sessionId).enqueued()) {
            // 사전 체크를 통과한 동시 요청 간 경합 — 한쪽만 적재되고 나머지는 여기서 409.
            throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        }
    }
}
