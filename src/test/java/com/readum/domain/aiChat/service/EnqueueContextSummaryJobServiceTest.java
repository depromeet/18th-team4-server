package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.model.aiChat.repository.AiChatContextSummaryJobRepository;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 컨텍스트 요약 적재 입구의 접수 계약.
 * 차단 중에는 적재하지 않고, 그 확인은 값싼 DB 판정을 통과한 뒤에만 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnqueueContextSummaryJobServiceTest {

    private static final Long SESSION_ID = 42L;
    private static final AiAvailability.Capability CAPABILITY = AiAvailability.Capability.CONTEXT_SUMMARY;

    @Mock private AiChatContextSummaryJobRepository jobRepository;
    @Mock private AiChatContextSummaryRepository summaryRepository;
    @Mock private AiChatMessageRepository messageRepository;
    @Mock private ContextSummaryJobInserter inserter;
    @Mock private AiAvailability aiAvailability;

    private final AiChatProperties aiChatProperties = new AiChatProperties(
            new AiChatProperties.Context(8000, 2000, 4000, 800),
            new AiChatProperties.MessageRule(1000),
            new AiChatProperties.RateLimit(10, 5),
            new AiChatProperties.TokenBudget(120000, 512),
            new AiChatProperties.Streaming(120, 30, 150, 60, 60, 60, 256, 300));

    private EnqueueContextSummaryJobService service;

    @BeforeEach
    void setUp() {
        service = new EnqueueContextSummaryJobService(
                jobRepository, summaryRepository, messageRepository, inserter, aiChatProperties, aiAvailability);
        // 임계값을 넘겨 적재 대상이 되게 한다.
        given(summaryRepository.findBySessionId(SESSION_ID)).willReturn(Optional.empty());
        given(messageRepository.sumRecentMessageTokens(eq(SESSION_ID), anyLong())).willReturn(9_000L);
        given(jobRepository.existsByActiveSessionId(SESSION_ID)).willReturn(false);
    }

    @Test
    void 공급자가_정상이면_작업을_적재한다() {
        service.enqueueIfRecentMessagesExceedThreshold(SESSION_ID);

        verify(aiAvailability).requireAvailable(CAPABILITY);
        verify(inserter).insertPending(SESSION_ID);
    }

    @Test
    void 이미_활성_작업이_있어_unique_경합이_나면_조용히_넘어간다() {
        willThrow(new DataIntegrityViolationException("uk_active_session"))
                .given(inserter).insertPending(SESSION_ID);
        given(jobRepository.existsByActiveSessionId(SESSION_ID)).willReturn(false, true);

        service.enqueueIfRecentMessagesExceedThreshold(SESSION_ID);

        verify(inserter).insertPending(SESSION_ID);
    }

    @Test
    void 공급자가_막혀_있으면_적재하지_않고_채팅으로_던지지도_않는다() {
        willThrow(new AiDependencyUnavailableException(AiChatErrorCode.AI_PROVIDER_UNAVAILABLE))
                .given(aiAvailability).requireAvailable(CAPABILITY);

        service.enqueueIfRecentMessagesExceedThreshold(SESSION_ID);

        verify(inserter, never()).insertPending(anyLong());
    }

    @Test
    void 임계값을_넘지_않으면_공급자_상태를_묻지도_않는다() {
        // 대부분의 턴은 여기서 끝난다 — 적재할 일이 없는 턴마다 Redis 를 두드릴 이유가 없다.
        given(messageRepository.sumRecentMessageTokens(eq(SESSION_ID), anyLong())).willReturn(10L);

        service.enqueueIfRecentMessagesExceedThreshold(SESSION_ID);

        verify(aiAvailability, never()).requireAvailable(any());
        verify(inserter, never()).insertPending(anyLong());
    }
}
