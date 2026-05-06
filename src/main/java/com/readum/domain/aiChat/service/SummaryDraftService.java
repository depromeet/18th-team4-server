package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Service
public class SummaryDraftService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiSummaryClient aiSummaryClient;
    private final UserBookRepository userBookRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final TransactionTemplate transactionTemplate;

    public SummaryDraftService(
            UserRepository userRepository,
            AiChatSessionRepository aiChatSessionRepository,
            AiChatMessageRepository aiChatMessageRepository,
            AiSummaryClient aiSummaryClient,
            UserBookRepository userBookRepository,
            SummaryRepository summaryRepository,
            SummaryDraftPolicy summaryDraftPolicy,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.aiChatSessionRepository = aiChatSessionRepository;
        this.aiChatMessageRepository = aiChatMessageRepository;
        this.aiSummaryClient = aiSummaryClient;
        this.userBookRepository = userBookRepository;
        this.summaryRepository = summaryRepository;
        this.summaryDraftPolicy = summaryDraftPolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * LLM 호출 동안 DB 커넥션과 비관적 락을 점유하지 않도록 트랜잭션을 세 단계로 분리한다.
     *
     * TX1 (비관적 락, 짧게): 검증 → 세션 종료 → Summary(IN_PROGRESS) 생성 → 커밋 (락 해제)
     * TX 밖              : LLM 호출
     * TX2 (짧게, 성공)   : Summary content 채우고 COMPLETED
     * TX3 (짧게, 실패)   : Summary → FAILED
     */
    public SummaryDraftResult execute(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        // TX1: 검증 + 세션 종료 + Summary(IN_PROGRESS) 선점
        PreparedContext ctx = transactionTemplate.execute(status -> {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

            userBookRepository.findByIdAndUserId(session.getUserBookId(), user.getId())
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

            summaryDraftPolicy.assertEligible(session);

            List<AiChatMessage> messages =
                    aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);

            session.close();

            Summary summary = summaryRepository.save(
                    Summary.createInProgress(session.getUserBookId(), sessionId));

            return new PreparedContext(summary.getId(), messages);
        });

        // TX 밖: LLM 호출 (커넥션·락 미점유)
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(ctx.messages());
        } catch (Exception e) {
            // TX3: AI 실패 → FAILED 마킹
            transactionTemplate.executeWithoutResult(status ->
                    summaryRepository.findById(ctx.summaryId())
                            .ifPresent(Summary::fail));
            log.error("감상문 생성 실패 sessionId={}", sessionId, e);
            throw e;
        }

        // TX2: AI 성공 → content 채우고 COMPLETED
        transactionTemplate.executeWithoutResult(status ->
                summaryRepository.findById(ctx.summaryId())
                        .ifPresent(summary -> summary.complete(result.title(), result.body(), result.quote())));

        return result;
    }

    private record PreparedContext(Long summaryId, List<AiChatMessage> messages) {}
}
