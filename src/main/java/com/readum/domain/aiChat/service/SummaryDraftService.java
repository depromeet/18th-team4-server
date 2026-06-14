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
     * LLM 호출과 결과 기록은 @Async 메서드에서 백그라운드로 처리된다.
     * 감상문 행은 미리 만들지 않는다 — "생성 중" 은 세션 LOCKED 상태가 표현하고,
     * 성공했을 때만 COMPLETED 행을 새로 기록한다(write-once). 실패 시엔 행을 만들지 않고 로그만 남긴다.
     * 중복 생성 방지: 비관적 락 + "ACTIVE 일 때만 LOCKED 전이"(SummaryDraftPolicy) 조합이 막는다.
     */
    public void execute(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

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

        generateAsync(sessionId, preparedContext);
    }

    /**
     * 사용자 요청 경로의 백그라운드 생성. @Async 로 별도 스레드에서 실행되므로 호출자는 즉시 반환된다.
     */
    @Async
    public void generateAsync(Long sessionId, PreparedContext preparedContext) {
        generateAndRecord(sessionId, preparedContext);
    }

    /**
     * 스케줄러 전용 독후감 생성. 인증 없이 세션 id 로 동작한다.
     * 마지막 요약 이후가 아니라 세션 전체 대화 이력으로 매번 다시 요약한다(증분 아님).
     * 수동 경로와 동일하게 lock → 생성 → 성공 시 새 COMPLETED 행 + unlock / 실패 시 로그 + unlock.
     * 이미 LOCKED 인 세션(다른 생성이 진행 중)은 건너뛴다.
     */
    public void executeForScheduler(Long sessionId) {
        PreparedContext preparedContext = transactionTemplate.execute(status -> {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
            if (session.isLocked()) {
                return null;
            }
            List<AiChatMessage> messages =
                    aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);
            session.lock();
            return new PreparedContext(session.getUserBookId(), messages);
        });

        if (preparedContext == null) {
            return;
        }
        generateAndRecord(sessionId, preparedContext);
    }

    /**
     * TX 밖에서 LLM 을 호출하고 결과를 반영한다.
     * 성공: COMPLETED 행 생성 + 세션 잠금 해제(unlock)를 한 트랜잭션으로.
     * 실패: 원인을 로그로 남기고 세션만 unlock — 감상문 행은 만들지 않는다.
     * 알려진 한계: 생성 도중 프로세스가 죽으면 세션이 LOCKED 로 남는다 (복구 정책은 별도 이슈).
     */
    private void generateAndRecord(Long sessionId, PreparedContext preparedContext) {
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(preparedContext.messages());
        } catch (Exception e) {
            log.error("감상문 생성 실패 sessionId={}", sessionId, e);
            transactionTemplate.executeWithoutResult(status ->
                    aiChatSessionRepository.findById(sessionId).ifPresentOrElse(
                            AiChatSession::unlock,
                            () -> log.warn("감상문 생성 종료 후 잠금 해제할 세션을 찾지 못함 sessionId={}", sessionId)));
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            summaryRepository.save(Summary.createCompleted(
                    preparedContext.userBookId(), sessionId, result.title(), result.body()));
            aiChatSessionRepository.findById(sessionId).ifPresentOrElse(
                    AiChatSession::unlock,
                    () -> log.warn("감상문 생성 종료 후 잠금 해제할 세션을 찾지 못함 sessionId={}", sessionId));
        });
    }

    public record PreparedContext(Long userBookId, List<AiChatMessage> messages) {}
}
