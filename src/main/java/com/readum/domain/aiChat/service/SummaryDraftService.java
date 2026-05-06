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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 감상문 초안 생성 흐름을 오케스트레이션한다.
 * 외부 LLM 호출 동안 DB 커넥션과 락을 잡지 않도록 트랜잭션을 세 단계로 쪼개고,
 * 첫 단계에서 SUMMARIZING 마커를 commit 해 polling 클라이언트가 상태 전이를 관찰할 수 있게 한다.
 *
 * 단계 1 (Tx) findByIdForUpdate → 소유권/정책 검증 → markSummarizing → 메시지 로드
 * 단계 2 (외부) aiSummaryClient.generate
 * 단계 3 (Tx) close 마킹
 * 에러 보상 (Tx) SUMMARIZING → ACTIVE 복구
 *
 * AiChatSessionTitleService 와 동일한 TransactionTemplate 패턴을 사용한다.
 */
@Slf4j
@Service
public class SummaryDraftService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final UserBookRepository userBookRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final AiSummaryClient aiSummaryClient;
    private final TransactionTemplate transactionTemplate;

    public SummaryDraftService(
            AiChatSessionRepository aiChatSessionRepository,
            AiChatMessageRepository aiChatMessageRepository,
            UserBookRepository userBookRepository,
            SummaryDraftPolicy summaryDraftPolicy,
            AiSummaryClient aiSummaryClient,
            PlatformTransactionManager transactionManager
    ) {
        this.aiChatSessionRepository = aiChatSessionRepository;
        this.aiChatMessageRepository = aiChatMessageRepository;
        this.userBookRepository = userBookRepository;
        this.summaryDraftPolicy = summaryDraftPolicy;
        this.aiSummaryClient = aiSummaryClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public SummaryDraftResult execute(Long sessionId, Long userId) {
        List<AiChatMessage> messages = transactionTemplate.execute(status -> {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

            userBookRepository.findByIdAndUserId(session.getUserBookId(), userId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

            summaryDraftPolicy.assertEligible(session);

            session.markSummarizing();

            return aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);
        });

        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(messages);
        } catch (RuntimeException ex) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        aiChatSessionRepository.findByIdForUpdate(sessionId)
                                .filter(AiChatSession::isSummarizing)
                                .ifPresent(AiChatSession::revertToActive));
            } catch (RuntimeException revertEx) {
                log.error("SUMMARIZING -> ACTIVE 복구 실패 sessionId={}", sessionId, revertEx);
            }
            throw ex;
        }

        transactionTemplate.executeWithoutResult(status -> {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
            session.close();
        });

        return result;
    }
}
