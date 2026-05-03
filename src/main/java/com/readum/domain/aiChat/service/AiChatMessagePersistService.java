package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.history.ChatHistoryBuilder;
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
    private final ChatHistoryBuilder chatHistoryBuilder;
    private final AiChatSessionTitleService aiChatSessionTitleService;

    /**
     * 세션 소유권 및 종료 여부 검증, 이전 이력 조회, USER 메시지 INSERT 까지를
     * 단일 트랜잭션으로 처리한다.
     * 호출 순서 주의: 이전 이력 조회를 USER 메시지 save 전에 수행한다.
     * Hibernate auto-flush 로 인해 save 후에 조회하면 방금 저장한 USER 메시지가
     * 결과에 포함되어 LLM 컨텍스트에 중복으로 들어가게 된다.
     */
    @Transactional
    public List<HistoryMessage> loadHistoryAndRecordUserMessage(
            Long sessionId, Long userId, String normalizedContent
    ) {
        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
        if (session.isClosed()) {
            throw new BadRequestException(AiChatErrorCode.SESSION_CLOSED);
        }
        List<HistoryMessage> previousHistory = chatHistoryBuilder.buildPreviousHistory(sessionId);
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, normalizedContent));
        session.appendUserMessage();
        if (session.isFirstUserMessage()) {
            scheduleTitleGenerationAfterCommit(sessionId, normalizedContent);
        }
        return previousHistory;
    }

    /**
     * 첫 USER 메시지 commit 직후 세션 제목을 비동기로 생성한다.
     * 설계 의도:
     * - commit 전에 호출하면 LLM 실패가 USER 메시지 영속화까지 롤백시킨다 — afterCommit 으로 분리.
     * - afterCommit 콜백은 호출 스레드(보통 servlet 요청 스레드) 에서 실행되므로,
     *   LLM blocking 호출은 boundedElastic 으로 즉시 던져 응답 latency 에 영향을 주지 않게 한다.
     * - 제목 생성이 실패해도 사용자 흐름과 무관하므로 예외는 위로 던지지 않고 로그만 남긴다.
     * - 확장성: 향후 N턴 재생성·수동 재명명 트리거가 추가되어도 동일한
     * {@link AiChatSessionTitleService#execute(GenerateSessionTitleCommand)} 진입점만 호출하면 된다.
     */
    private void scheduleTitleGenerationAfterCommit(Long sessionId, String firstUserMessage) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖에서 직접 호출되는 경우(테스트/배치) 는 skip — 호출자가 직접 titleService 호출.
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Mono.fromRunnable(() -> aiChatSessionTitleService.execute(
                                new GenerateSessionTitleCommand(sessionId, firstUserMessage)))
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
        if (totalTokens != null && totalTokens > 0) {
            aiChatSessionRepository.findById(sessionId)
                    .ifPresent(session -> session.addAssistantTokens(totalTokens));
        }
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
        if (totalTokens != null && totalTokens > 0) {
            aiChatSessionRepository.findById(sessionId)
                    .ifPresent(session -> session.addAssistantTokens(totalTokens));
        }
    }
}
