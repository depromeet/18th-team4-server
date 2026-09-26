package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AssembledContext;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.event.ContextSummarizeTriggerEvent;
import com.readum.domain.aiChat.event.FirstAssistantResponseCompletedEvent;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final SummaryJobRepository summaryJobRepository;
    private final TokenCounter tokenCounter;
    // 제목 생성을 직접 호출하지 않고 "첫 응답 완료" 이벤트만 발행한다. 실제 생성은 AFTER_COMMIT 리스너가 담당(결합 분리).
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 세션 소유권 및 잠김 여부를 검증하고 이전 이력만 조회한다(USER 메시지는 저장하지 않음).
     * 입력 moderation 을 SSE 시작 전에 동기 실행하기 위해, 저장(record*) 과 분리했다.
     * 어노테이션 없음: 단순 조회 service 이며, 호출하는 Repository 가 이미 SimpleJpaRepository 의
     * readOnly 트랜잭션 안에서 실행되어 @Transactional(readOnly) 효과가 중복되고,
     * 세션 검증 + 이력 조회의 일관 스냅샷은 비즈니스 요구가 아니다(CLAUDE.md Transaction Convention).
     */
    public MessageLoadResult loadHistory(Long sessionId, Long userId) {
        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, userId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));
        if (session.isLocked()) {
            // 감상문이 완성되어 종료된 세션 — 영구히 대화 불가
            throw new BadRequestException(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED);
        }
        if (summaryJobRepository.existsBlockingSummaryJob(sessionId, LocalDateTime.now())) {
            // 지금 생성 중 — 일시적으로 전송 불가
            throw new BadRequestException(AiChatErrorCode.SESSION_LOCKED);
        }
        AssembledContext assembled = aiChatHistorySearchService.assembleContext(sessionId);
        return new MessageLoadResult(assembled.summary(), assembled.recentMessages(), session.getUserBookId());
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
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(
                sessionId, normalizedContent, tokenCounter.count(normalizedContent)));
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

    /**
     * @param notSummarizedChatRaws 요약 경계 이후의 원문 대화(요약이 대체하지 못한 최근 꼬리). 전체 이력이 아니다 —
     *                              앞부분은 {@code contextSummary} 가 대체한다. 현재 보내는 USER 메시지는 아직 포함하지 않는다.
     */
    public record MessageLoadResult(String contextSummary, List<HistoryMessage> notSummarizedChatRaws, Long userBookId) {}

    @Transactional
    public AiChatMessage saveAssistantSuccess(
            Long sessionId, String accumulated, AiChatCompletion meta
    ) {
        Integer inputTokens = meta == null ? null : meta.inputTokens();
        Integer outputTokens = meta == null ? null : meta.outputTokens();
        Integer totalTokens = meta == null ? null : meta.totalTokens();
        // token_count = ASSISTANT 실측 출력. 비정상적으로 usage 가 없으면 응답 텍스트를 직접 센다.
        Integer tokenCount = outputTokens != null ? outputTokens : tokenCounter.count(accumulated);

        AiChatMessage saved = aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(
                sessionId, accumulated, inputTokens, outputTokens, totalTokens, tokenCount
        ));
        // ASSISTANT 응답이 COMPLETED 로 쌓였으니, 커밋 후 컨텍스트 요약이 필요한지(최근 원문 대화 토큰 합 > 임계값) 판정하도록 트리거한다.
        // 실제 임계값 검사·job 적재는 AFTER_COMMIT 리스너가 담당한다(사용자 응답 경로와 분리, LLM 요약은 워커가 비동기 처리).
        eventPublisher.publishEvent(new ContextSummarizeTriggerEvent(sessionId));
        // 세션 누적치는 ASSISTANT 가 생성한 토큰만 합산한다.
        // totalTokens 는 입력 프롬프트(이전 대화 + 시스템 프롬프트) 까지 포함하므로 누적에 쓰면
        // 같은 컨텍스트가 매 턴 중복 집계되어 실제 생성량보다 부풀려진다.
        // 첫 ASSISTANT 응답이 성공 저장된 시점에, 유저의 첫 질문(첫 COMPLETED USER 메시지)을 담아 "첫 응답 완료" 이벤트를 발행한다.
        // 실제 제목 생성은 AFTER_COMMIT 리스너가 담당한다(커밋 후 실행 → LLM 실패가 이 트랜잭션을 롤백시키지 않음).
        // 어시스턴트 응답까지 합치지 않고 첫 질문만 쓰는 이유: 제목은 사용자가 무엇을 물었는지를 요약하면 충분하고,
        // 긴 답변을 함께 넣으면 주제가 희석된다. (재생성/수동 재명명 등 다른 트리거는 각자 입력을 구성해 호출한다.)
        aiChatSessionRepository.findById(sessionId).ifPresent(session -> {
            if (outputTokens != null && outputTokens > 0) {
                session.addAssistantTokens(outputTokens);
            }
            if (session.isFirstUserMessage()) {
                aiChatMessageRepository.findFirstUserMessage(sessionId)
                        .ifPresent(firstUserMessage -> eventPublisher.publishEvent(
                                new FirstAssistantResponseCompletedEvent(sessionId, firstUserMessage)));
            }
        });
        return saved;
    }
}
