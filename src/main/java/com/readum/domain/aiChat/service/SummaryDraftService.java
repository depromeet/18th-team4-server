package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 수동(데모) 감상문 생성 요청 진입점. 직접 생성하지 않고 작업을 적재한다.
 * 실제 생성은 작업 큐 워커(SummaryGenerationWorker)가 처리한다.
 * 자격(ACTIVE + 누적 토큰 ≥ 임계값, 종료 세션 제외)은 SummaryDraftPolicy 가 검증한다.
 * 중복 적재는 EnqueueSummaryJobService 의 멱등성(active_session_id unique)이 막는다.
 *
 * <p><b>단계 순서에 뜻이 있다.</b> 이미 활성 작업이 있는 세션의 재요청은 공급자 상태와 무관하게
 * "이미 접수됨(409)" 으로 답한다 — 사용자가 보기에 그 요청은 예전에 이미 받아들여졌고, 공급자가 막혔다고 해서
 * 그 사실이 달라지지 않는다. 공급자 가용 확인은 <b>정말로 새 작업을 만들 때</b>만 한다.
 *
 * <p><b>@Transactional 을 두지 않는다.</b> 공급자 상태 확인은 Redis 왕복이라 DB 트랜잭션 안에서 하면
 * 그 시간만큼 커넥션과 행 잠금을 붙잡는다. 여기서 하는 일은 조회 몇 개와 멱등 적재라 한 트랜잭션으로 묶을 필요가 없다 —
 * 동시 요청 간의 정확성은 트랜잭션 경계가 아니라 active_session_id 의 unique 제약이 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryDraftService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final UserBookRepository userBookRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final AiAvailability aiAvailability;
    private final SummaryJobRepository summaryJobRepository;
    private final EnqueueSummaryJobService enqueueSummaryJobService;

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

        // 공급자가 막혀 있거나 차단 뒤 쌓인 작업을 비우는 중이면 새 접수를 받지 않는다 —
        // 그 상태에서 받아 두면 이미 기다리던 작업들 뒤에 줄이 더 길어지기만 한다.
        // 차단 중에는 예외가 없다. 복구를 확인하는 것은 전용 스케줄러의 일이고, 사용자의 요청이
        // 그 확인을 겸하지 않는다 — 아직 고쳐졌는지 모르는 공급자를 향해 사용자를 기다리게 하지 않기 위해서다.
        aiAvailability.requireAvailable(AiAvailability.Capability.SUMMARY);

        if (!enqueueSummaryJobService.execute(sessionId).enqueued()) {
            // 사전 체크를 통과한 동시 요청 간 경합 — 한쪽만 적재되고 나머지는 여기서 409.
            throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        }
    }
}
