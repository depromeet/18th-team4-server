package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * AI 채팅 메시지의 영속화 책임을 담당하는 service.
 * Command/Query service 형태가 아니라 영속화 책임만 모아둔 service 인 이유:
 * AiChatMessageSendService 의 Reactor 콜백 안에서 @Transactional 메서드를
 * "외부 호출"(프록시 통과) 로 만들기 위해 별도 service 로 분리했다 (self-invocation 회피).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatMessagePersistService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatHistorySearchService aiChatHistorySearchService;
    private final AiChatSessionTitleService aiChatSessionTitleService;

    /**
     * 세션 소유권 및 종료 여부를 검증하고 이전 이력만 조회한다(USER 메시지는 저장하지 않음).
     * 입력 moderation 을 SSE 시작 전에 동기 실행하기 위해, 저장(record*) 과 분리했다.
     * 어노테이션 없음: 단순 조회 service 이며, 호출하는 Repository 가 이미 SimpleJpaRepository 의
     * readOnly 트랜잭션 안에서 실행되어 @Transactional(readOnly) 효과가 중복되고,
     * 세션 검증 + 이력 조회의 일관 스냅샷은 비즈니스 요구가 아니다(CLAUDE.md Transaction Convention).
     */
    public MessageLoadResult loadHistory(Long sessionId, Long userId) {
        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
        if (session.isClosed()) {
            throw new BadRequestException(AiChatErrorCode.SESSION_CLOSED);
        }
        List<HistoryMessage> previousHistory = aiChatHistorySearchService.findPreviousHistory(sessionId);
        return new MessageLoadResult(previousHistory, session.getUserBookId());
    }

    /**
     * moderation 통과한 USER 메시지를 COMPLETED 로 저장하고 세션 턴 카운트를 증가시킨다.
     * 반드시 {@link #loadHistory(Long, Long)} 로 검증을 마친 뒤 호출한다.
     * 동시성 가정: 같은 세션에 sequential 호출만 들어온다고 본다 (UI 의 전송 중 비활성 +
     * application.yml 의 ai-chat.rate-limit 으로 1차 방어). 따라서 session 의
     * userMessageCount read-modify-write 에 락을 걸지 않는다.
     * 동시 호출이 겹치면 카운트 1회 유실이 가능하나 비즈니스 결정에 영향이 없어 허용.
     * 트래픽 패턴이 바뀌면 @Version 최적화 락 도입을 검토.
     */
    @Transactional
    public void recordUserMessage(Long sessionId, Long userId, String normalizedContent) {
        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, normalizedContent));
        session.appendUserMessage();
    }

    /**
     * 입력 가드레일에 차단된 USER 메시지를 REJECTED 로 저장만 한다.
     * 반드시 {@link #loadHistory(Long, Long)} 로 세션 검증을 마친 뒤 호출한다 — 여기선 저장만 하고 검증하지 않는다.
     * 세션 턴 카운트(appendUserMessage) 는 증가시키지 않는다(거부 메시지는 정상 대화 턴이 아님).
     * REJECTED 가 첫 USER 메시지 자리에 끼어도 제목 생성 트리거는 그 다음 정상 USER 메시지가 처음 도착할 때 발동한다.
     */
    @Transactional
    public void recordRejectedUserMessage(Long sessionId, String normalizedContent) {
        aiChatMessageRepository.save(AiChatMessage.createUserMessageRejected(sessionId, normalizedContent));
    }

    public record MessageLoadResult(List<HistoryMessage> history, Long userBookId) {}

    /**
     * 첫 ASSISTANT 응답 commit 직후 대화 이력 기반으로 세션 제목을 비동기로 생성한다.
     * 설계 의도:
     * - commit 전에 호출하면 LLM 실패가 ASSISTANT 메시지 영속화까지 롤백시킨다 — afterCommit 으로 분리.
     * - afterCommit 콜백은 호출 스레드(보통 servlet 요청 스레드) 에서 실행되므로,
     *   LLM blocking 호출은 boundedElastic 으로 즉시 던져 응답 latency 에 영향을 주지 않게 한다.
     * - 제목 생성이 실패해도 사용자 흐름과 무관하므로 예외는 위로 던지지 않고 로그만 남긴다.
     * - 확장성: 향후 수동 재명명 트리거가 추가되어도 동일한
     * {@link AiChatSessionTitleService#execute(GenerateSessionTitleCommand)} 진입점만 호출하면 된다.
     */
    private void scheduleTitleGenerationAfterCommit(Long sessionId, List<AiChatMessage> messages) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖에서 직접 호출되는 경우(테스트/배치) 는 skip — 호출자가 직접 titleService 호출.
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Mono.fromRunnable(() -> aiChatSessionTitleService.execute(
                                new GenerateSessionTitleCommand(sessionId, messages)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                null,
                                error -> log.error("세션 제목 생성 실패 sessionId={}", sessionId, error)
                        );
            }
        });
    }

    @Transactional
    public AiChatMessage saveAssistantSuccess(
            Long sessionId, String accumulated, AiChatChunk.Completion meta
    ) {
        Integer inputTokens = meta == null ? null : meta.inputTokens();
        Integer outputTokens = meta == null ? null : meta.outputTokens();
        Integer totalTokens = meta == null ? null : meta.totalTokens();

        AiChatMessage saved = aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(
                sessionId, accumulated, inputTokens, outputTokens, totalTokens
        ));
        // 세션 누적치는 ASSISTANT 가 생성한 토큰만 합산한다.
        // totalTokens 는 입력 프롬프트(이전 대화 + 시스템 프롬프트) 까지 포함하므로 누적에 쓰면
        // 같은 컨텍스트가 매 턴 중복 집계되어 실제 생성량보다 부풀려진다.
        // 첫 ASSISTANT 응답 완료(첫 교환) 시점에 대화 이력 기반으로 세션 제목을 생성한다.
        aiChatSessionRepository.findById(sessionId).ifPresent(session -> {
            if (outputTokens != null && outputTokens > 0) {
                session.addAssistantTokens(outputTokens);
            }
            if (session.isFirstUserMessage()) {
                List<AiChatMessage> messages =
                        aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);
                scheduleTitleGenerationAfterCommit(sessionId, messages);
            }
        });
        return saved;
    }

    @Transactional
    public void saveAssistantFailed(
            Long sessionId, String partial, AiChatChunk.Completion meta
    ) {
        Integer inputTokens = meta == null ? null : meta.inputTokens();
        Integer outputTokens = meta == null ? null : meta.outputTokens();
        Integer totalTokens = meta == null ? null : meta.totalTokens();

        aiChatMessageRepository.save(AiChatMessage.createAssistantFailed(
                sessionId, partial == null ? "" : partial, inputTokens, outputTokens, totalTokens
        ));
        // 성공 경로와 동일하게 입력 토큰은 누적에서 제외하고 outputTokens 만 합산.
        if (outputTokens != null && outputTokens > 0) {
            aiChatSessionRepository.findById(sessionId)
                    .ifPresent(session -> session.addAssistantTokens(outputTokens));
        }
    }
}
