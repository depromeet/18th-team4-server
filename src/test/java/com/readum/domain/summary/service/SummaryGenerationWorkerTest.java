package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiCallCircuitBreaker;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.domain.summary.out.SummaryCallRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryGenerationWorkerTest {

    @Mock private SummaryJobLifecycleService lifecycleService;
    @Mock private AiSummaryClient aiSummaryClient;
    @Mock private AiCallCircuitBreaker circuitBreaker;
    @Mock private SummaryCallRateLimiter rateLimiter;
    @Mock private SummaryTokenEstimator tokenEstimator;
    @Mock private SummaryJobProperties properties;

    @InjectMocks private SummaryGenerationWorker worker;

    /** breaker 닫힘 + 작업 1건 선점 + 컨텍스트 준비 + 토큰 추정까지 진행되는 공통 경로(페이서 결과는 각 테스트가 지정). */
    private void givenClaimedJobWithContext() {
        given(circuitBreaker.isOpen()).willReturn(false);
        given(lifecycleService.claimOne(anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 7L, List.of()));
        given(properties.reservedOutputTokens()).willReturn(1024);
        given(tokenEstimator.estimate(any(), eq(1024))).willReturn(2000);
    }

    @Test
    void breaker가_열려있으면_선점도_생성도_하지_않는다() {
        given(circuitBreaker.isOpen()).willReturn(true);

        boolean processed = worker.processOne();

        assertThat(processed).isFalse();
        verify(lifecycleService, never()).claimOne(anyString());
        verify(aiSummaryClient, never()).generate(any());
    }

    @Test
    void 처리할_작업이_없으면_생성을_호출하지_않는다() {
        given(circuitBreaker.isOpen()).willReturn(false);
        given(lifecycleService.claimOne(anyString())).willReturn(null);

        worker.processOne();

        verify(aiSummaryClient, never()).generate(any());
    }

    @Test
    void 정상_흐름은_페이서_통과후_생성하고_성공을_기록한다() throws InterruptedException {
        givenClaimedJobWithContext();
        given(rateLimiter.tryAcquire(2000)).willReturn(true);
        given(aiSummaryClient.generate(any())).willReturn(new SummaryDraftResult("제목", "본문"));

        worker.processOne();

        verify(rateLimiter).tryAcquire(2000);
        verify(lifecycleService).recordSuccess(eq(10L), anyString(), eq(7L), any(SummaryDraftResult.class));
    }

    @Test
    void 페이서_예산이_부족하면_생성하지않고_작업을_되돌린다() throws InterruptedException {
        givenClaimedJobWithContext();
        given(rateLimiter.tryAcquire(2000)).willReturn(false);
        given(properties.pacedRetrySeconds()).willReturn(30L);

        worker.processOne();

        verify(aiSummaryClient, never()).generate(any());
        verify(lifecycleService).requeue(eq(10L), anyString(), any(LocalDateTime.class));
        verify(lifecycleService, never()).recordFailure(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyString(), any(), any());
    }

    @Test
    void 준비단계가_null이면_생성을_건너뛴다() {
        given(circuitBreaker.isOpen()).willReturn(false);
        given(lifecycleService.claimOne(anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString())).willReturn(null);

        worker.processOne();

        verify(aiSummaryClient, never()).generate(any());
        verify(lifecycleService, never()).recordSuccess(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void quota_소진이면_breaker를_열고_재시도로_기록한다() throws InterruptedException {
        givenClaimedJobWithContext();
        given(rateLimiter.tryAcquire(2000)).willReturn(true);
        given(properties.breakerOpen()).willReturn(Duration.ofMinutes(10));
        given(aiSummaryClient.generate(any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED, null));

        worker.processOne();

        verify(circuitBreaker).openFor(Duration.ofMinutes(10));
        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(true), anyString(), any(), isNull());
    }

    @Test
    void burst_429면_breaker는_안열고_RetryAfter로_재시도한다() throws InterruptedException {
        givenClaimedJobWithContext();
        given(rateLimiter.tryAcquire(2000)).willReturn(true);
        RateLimitInfo info = new RateLimitInfo(Duration.ofSeconds(30), null, null, null, null, null, null);
        given(aiSummaryClient.generate(any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, info));

        worker.processOne();

        verify(circuitBreaker, never()).openFor(any());
        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(true), anyString(), any(),
                any(LocalDateTime.class));
    }

    @Test
    void 비재시도_4xx면_즉시_FAILED로_기록한다() throws InterruptedException {
        givenClaimedJobWithContext();
        given(rateLimiter.tryAcquire(2000)).willReturn(true);
        given(aiSummaryClient.generate(any())).willThrow(new NonTransientAiException("bad request"));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(false), anyString(), any(), isNull());
    }

    @Test
    void 일시_5xx면_재시도로_기록한다() throws InterruptedException {
        givenClaimedJobWithContext();
        given(rateLimiter.tryAcquire(2000)).willReturn(true);
        given(aiSummaryClient.generate(any())).willThrow(new TransientAiException("server error"));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(true), anyString(), any(), isNull());
    }
}
