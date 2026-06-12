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
import org.springframework.scheduling.annotation.Async;
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
     * TX1(검증 + 세션 잠금)을 동기로 완료한 뒤 즉시 반환한다.
     * LLM 호출과 결과 기록(TX2)은 @Async 메서드에서 백그라운드로 처리된다.
     * 감상문 행은 미리 만들지 않는다 — "생성 중" 은 세션 LOCKED 상태가 표현하고,
     * 감상문(Summary) 은 생성 시도가 끝난 시점에 결과(COMPLETED/FAILED)와 함께 한 번만 기록된다.
     * 중복 생성 방지: 비관적 락 + "ACTIVE 일 때만 LOCKED 전이"(SummaryDraftPolicy) 조합이 막는다.
     */
    public void execute(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        // TX1: 검증 + 세션 잠금 — 커밋 후 즉시 반환
        PreparedContext preparedContext = transactionTemplate.execute(status -> {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

            userBookRepository.findByIdAndUserId(session.getUserBookId(), user.getId())
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

            summaryDraftPolicy.assertEligible(session);

            List<AiChatMessage> messages =
                    aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);

            session.lock();

            return new PreparedContext(session.getUserBookId(), messages);
        });

        // 백그라운드에서 LLM 호출 + 결과 기록
        generateAsync(sessionId, preparedContext);
    }

    /**
     * TX 밖에서 LLM 을 호출하고 결과를 DB 에 반영한다.
     * @Async 로 별도 스레드에서 실행되므로 호출자는 즉시 반환된다.
     *
     * 성공/실패 모두 "감상문 행 기록 + 세션 잠금 해제(unlock)" 를 한 트랜잭션으로 묶는다 —
     * 세션은 다시 활성화됐는데 결과 행이 없는 어중간한 상태를 막기 위함.
     * 알려진 한계: 생성 도중 프로세스가 죽으면 세션이 LOCKED 로 남는다 (복구 정책은 별도 이슈).
     */
    @Async
    public void generateAsync(Long sessionId, PreparedContext preparedContext) {
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(preparedContext.messages());
        } catch (Exception e) {
            // TX2 (실패 경로): FAILED 행 기록 + 세션 잠금 해제
            transactionTemplate.executeWithoutResult(status -> {
                summaryRepository.save(Summary.createFailed(preparedContext.userBookId(), sessionId));
                aiChatSessionRepository.findById(sessionId).ifPresent(AiChatSession::unlock);
            });
            log.error("감상문 생성 실패 sessionId={}", sessionId, e);
            return;
        }

        // TX2 (성공 경로): COMPLETED 행 기록 + 세션 잠금 해제
        transactionTemplate.executeWithoutResult(status -> {
            summaryRepository.save(Summary.createCompleted(
                    preparedContext.userBookId(), sessionId,
                    result.title(), result.body(), result.quote()));
            aiChatSessionRepository.findById(sessionId).ifPresent(AiChatSession::unlock);
        });
    }

    public record PreparedContext(Long userBookId, List<AiChatMessage> messages) {}
}
