package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.dto.ContextSummaryGenerationContext;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiContextSummaryClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.out.AiQuotaCooldown;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 워커 드레인 루프 제어의 단위 테스트. 전역 게이트 포화(burst/quota 429)는 계정 전역 신호이므로 이번 드레인 사이클을
 * 멈춰야 한다 — 그러지 않으면 무벌점 반납한 작업을 예산이 찰 때까지 즉시 재선점하며 DB 를 두드리는 타이트 루프가 된다.
 */
@ExtendWith(MockitoExtension.class)
class ContextSummaryWorkerTest {

    private static final Long JOB_ID = 42L;
    private static final Long SESSION_ID = 7L;

    @Mock
    private ContextSummaryJobLifecycleService lifecycleService;
    @Mock
    private AiContextSummaryClient aiContextSummaryClient;
    @Mock
    private AiQuotaCooldown quotaCooldown;

    // 결정적 test double: 토큰 = 글자 수(null 은 0).
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    private final ContextSummaryJobProperties jobProperties =
            new ContextSummaryJobProperties(2, 2000, 60000, 120, 5, 60, 120000);
    private final AiChatProperties aiChatProperties = new AiChatProperties(
            new AiChatProperties.Context(8000, 2000, 4000, 800),
            new AiChatProperties.MessageRule(1000),
            new AiChatProperties.RateLimit(10, 5),
            new AiChatProperties.TokenBudget(120000, 512),
            new AiChatProperties.Streaming(120, 30, 150, 60, 256));

    private ContextSummaryWorker worker() {
        return new ContextSummaryWorker(
                lifecycleService, aiContextSummaryClient, quotaCooldown,
                tokenCounter, jobProperties, aiChatProperties);
    }

    private ContextSummaryGenerationContext generationContext() {
        return new ContextSummaryGenerationContext(SESSION_ID, null, null, List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "aa"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "bb")
        ), 2L);
    }

    @Test
    void burst_429_면_무벌점_반납하고_이번_드레인_사이클을_멈춘다() {
        given(quotaCooldown.isCoolingDown()).willReturn(false);
        given(lifecycleService.claimOne(anyString())).willReturn(JOB_ID);
        given(lifecycleService.prepareGeneration(eq(JOB_ID), anyString())).willReturn(generationContext());
        given(aiContextSummaryClient.generate(any(), any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST));

        // 멈추지 않으면 claimOne 이 계속 같은 job 을 돌려줘 무한 루프가 된다 — 이 호출이 끝난다는 것 자체가 멈춤의 증거.
        worker().processUntilEmpty();

        verify(aiContextSummaryClient, times(1)).generate(any(), any());
        verify(lifecycleService, times(1)).releaseWithoutPenalty(eq(JOB_ID), anyString());
        verify(lifecycleService, times(1)).claimOne(anyString());
    }

    @Test
    void quota_429_면_재시도_가능_실패로_기록하고_이번_드레인_사이클을_멈춘다() {
        given(quotaCooldown.isCoolingDown()).willReturn(false);
        given(lifecycleService.claimOne(anyString())).willReturn(JOB_ID);
        given(lifecycleService.prepareGeneration(eq(JOB_ID), anyString())).willReturn(generationContext());
        given(aiContextSummaryClient.generate(any(), any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED));

        worker().processUntilEmpty();

        verify(lifecycleService, times(1))
                .recordFailure(eq(JOB_ID), anyString(), eq(true), eq(AiChatErrorCode.AI_QUOTA_EXHAUSTED.name()), any(), any());
        verify(lifecycleService, times(1)).claimOne(anyString());
    }

    @Test
    void 정상_처리면_다음_작업이_없을_때까지_계속_드레인한다() {
        given(quotaCooldown.isCoolingDown()).willReturn(false);
        // 첫 사이클은 job 을 처리하고, 두 번째엔 없음 → 정상 종료.
        given(lifecycleService.claimOne(anyString())).willReturn(JOB_ID, (Long) null);
        given(lifecycleService.prepareGeneration(eq(JOB_ID), anyString())).willReturn(generationContext());
        given(aiContextSummaryClient.generate(any(), any()))
                .willReturn(new ContextSummaryResult("[누적 요약]"));

        worker().processUntilEmpty();

        verify(lifecycleService, times(1)).recordSuccess(eq(JOB_ID), anyString(), any(), any());
        verify(lifecycleService, times(2)).claimOne(anyString());
    }

    @Test
    void quota_쿨다운_중이면_job_을_선점하지_않는다() {
        given(quotaCooldown.isCoolingDown()).willReturn(true);

        worker().processUntilEmpty();

        verify(lifecycleService, times(0)).claimOne(anyString());
    }
}
