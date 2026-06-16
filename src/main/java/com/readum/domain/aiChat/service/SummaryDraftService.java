package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수동(데모) 감상문 생성 요청 진입점. 직접 생성하지 않고 작업을 적재한다.
 * 실제 생성은 작업 큐 워커(SummaryGenerationWorker)가 처리한다.
 * 자격(ACTIVE + 누적 토큰 ≥ 임계값, 종료 세션 제외)은 SummaryDraftPolicy 가 검증한다.
 * 중복 적재는 EnqueueSummaryJobService 의 멱등성(active_session_id unique)이 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryDraftService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final UserBookRepository userBookRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final EnqueueSummaryJobService enqueueSummaryJobService;

    @Transactional
    public void execute(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        AiChatSession session = aiChatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        userBookRepository.findByIdAndUserId(session.getUserBookId(), user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        summaryDraftPolicy.assertEligible(session);

        enqueueSummaryJobService.execute(sessionId);
    }
}
