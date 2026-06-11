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

import java.time.LocalDate;
import java.time.LocalDateTime;
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
     * TX1(검증 + IN_PROGRESS 저장)을 동기로 완료한 뒤 즉시 반환한다.
     * LLM 호출과 상태 업데이트(TX2/TX3)는 @Async 메서드에서 백그라운드로 처리된다.
     */
    public void execute(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        // TX1: 검증 + 세션 종료 + Summary(IN_PROGRESS) 선점 — 커밋 후 즉시 반환
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
                    Summary.createInProgress(session.getUserBookId(), sessionId, LocalDate.now()));

            return new PreparedContext(summary.getId(), messages);
        });

        // 백그라운드에서 LLM 호출 + 상태 업데이트
        generateAsync(sessionId, ctx);
    }

    /**
     * TX 밖에서 LLM을 호출하고 결과를 DB에 반영한다.
     * @Async 로 별도 스레드에서 실행되므로 호출자는 즉시 반환된다.
     *
     * TX2 (성공): Summary → COMPLETED
     * TX3 (실패): Summary → FAILED
     */
    @Async
    public void generateAsync(Long sessionId, PreparedContext ctx) {
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(ctx.messages());
        } catch (Exception e) {
            // TX3: AI 실패 → FAILED 마킹
            transactionTemplate.executeWithoutResult(status ->
                    summaryRepository.findById(ctx.summaryId())
                            .ifPresent(Summary::fail));
            log.error("감상문 생성 실패 sessionId={}", sessionId, e);
            return;
        }

        // TX2: AI 성공 → content 채우고 COMPLETED
        transactionTemplate.executeWithoutResult(status ->
                summaryRepository.findById(ctx.summaryId())
                        .ifPresent(summary -> summary.complete(result.title(), result.body(), result.quote())));
    }

    /**
     * 스케줄러 전용 독후감 생성.
     * 인증 없이 세션 ID 만으로 동작하며, 세션 상태를 변경하지 않는다.
     * 마지막 요약 이후 메시지를 대상으로 LLM 을 호출한다.
     *
     * TX1: 세션 조회 + 메시지 조회 + Summary(IN_PROGRESS) 저장
     * LLM 호출 (TX 밖)
     * TX2 (성공): Summary → COMPLETED
     * TX3 (실패): Summary → FAILED
     */
    public void executeForScheduler(Long aiChatSessionId, LocalDate summaryDate) {
        PreparedContext ctx = transactionTemplate.execute(status -> {
            AiChatSession session = aiChatSessionRepository.findById(aiChatSessionId)
                    .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

            LocalDateTime since = summaryRepository
                    .findFirstByAiChatSessionIdOrderByCreatedAtDesc(aiChatSessionId)
                    .map(Summary::getCreatedAt)
                    .orElse(session.getCreatedAt());

            List<AiChatMessage> messages = aiChatMessageRepository.findValidMessagesSince(aiChatSessionId, since);

            Summary summary = summaryRepository.save(
                    Summary.createInProgress(session.getUserBookId(), aiChatSessionId, summaryDate));

            return new PreparedContext(summary.getId(), messages);
        });

        generateAndUpdateStatus(aiChatSessionId, ctx);
    }

    private void generateAndUpdateStatus(Long sessionId, PreparedContext ctx) {
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(ctx.messages());
        } catch (Exception e) {
            transactionTemplate.executeWithoutResult(status ->
                    summaryRepository.findById(ctx.summaryId())
                            .ifPresent(Summary::fail));
            log.error("감상문 자동 생성 실패 sessionId={}", sessionId, e);
            return;
        }

        transactionTemplate.executeWithoutResult(status ->
                summaryRepository.findById(ctx.summaryId())
                        .ifPresent(summary -> summary.complete(result.title(), result.body(), result.quote())));
    }

    public record PreparedContext(Long summaryId, List<AiChatMessage> messages) {}
}
