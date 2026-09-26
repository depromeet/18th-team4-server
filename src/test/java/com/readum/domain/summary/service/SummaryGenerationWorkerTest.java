package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.domain.summary.out.AiQuotaCooldown;
import com.readum.domain.summary.out.ShutdownSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SummaryGenerationWorkerTest {

    @Mock SummaryJobLifecycleService lifecycleService;
    @Mock AiSummaryClient aiSummaryClient;
    @Mock AiQuotaCooldown quotaCooldown;
    @Mock ShutdownSignal shutdownSignal;
    @Mock SummaryTokenEstimator tokenEstimator;
    @Mock SummaryJobProperties properties;

    @InjectMocks SummaryGenerationWorker worker;

    private static final SummaryGenerationContext CONTEXT =
            new SummaryGenerationContext(100L, 200L, List.of());

    @BeforeEach
    void setup() {
        when(quotaCooldown.isCoolingDown()).thenReturn(false);
        when(shutdownSignal.isShuttingDown()).thenReturn(false);
        when(lifecycleService.claimOne(anyString())).thenReturn(1L);
        when(lifecycleService.prepareGeneration(eq(1L), anyString())).thenReturn(CONTEXT);
        when(properties.estimatedOutputTokens()).thenReturn(1024);
        when(tokenEstimator.estimate(any(), eq(1024))).thenReturn(3000);
        when(properties.maxRequestTokens()).thenReturn(120_000);
    }

    @Test
    void quota_쿨다운_중이면_선점하지_않고_종료한다() {
        when(quotaCooldown.isCoolingDown()).thenReturn(true);

        boolean processed = worker.processOne();

        org.assertj.core.api.Assertions.assertThat(processed).isFalse();
        verify(lifecycleService, never()).claimOne(anyString());
    }

    @Test
    void 종료_중이면_선점하지_않고_종료한다() {
        when(shutdownSignal.isShuttingDown()).thenReturn(true);

        boolean processed = worker.processOne();

        org.assertj.core.api.Assertions.assertThat(processed).isFalse();
        verify(lifecycleService, never()).claimOne(anyString());
    }

    @Test
    void 처리_도중_종료_신호가_오면_손에_든_작업은_마치고_새로_선점하지_않는다() throws Exception {
        // 첫 선점 직전엔 종료 중이 아니고, 그 작업을 처리하는 사이에 종료 신호가 도착한 상황.
        // 이미 집은 작업은 끝까지 마치고(recordSuccess 1회), 다음 작업은 집지 않아야 한다(claimOne 1회).
        when(shutdownSignal.isShuttingDown()).thenReturn(false, true);
        SummaryDraftResult result = new SummaryDraftResult("제목", "본문");
        when(aiSummaryClient.generate(any())).thenReturn(result);

        worker.processUntilEmpty();

        verify(lifecycleService, org.mockito.Mockito.times(1)).claimOne(anyString());
        verify(lifecycleService, org.mockito.Mockito.times(1))
                .recordSuccess(eq(1L), anyString(), eq(200L), eq(result));
        verify(lifecycleService, never())
                .recordFailure(anyLong(), anyString(), anyBoolean(), anyString(), anyString(), any());
    }

    @Test
    void 정상이면_생성하고_성공_기록한다() throws Exception {
        SummaryDraftResult result = new SummaryDraftResult("제목", "본문");
        when(aiSummaryClient.generate(any())).thenReturn(result);

        worker.processOne();

        verify(lifecycleService).recordSuccess(eq(1L), anyString(), eq(200L), eq(result));
    }

    @Test
    void 세션이_용량보다_크면_호출없이_즉시_실패한다() throws Exception {
        when(tokenEstimator.estimate(any(), eq(1024))).thenReturn(200_000);

        worker.processOne();

        verify(aiSummaryClient, never()).generate(any());
        verify(lifecycleService).recordFailure(eq(1L), anyString(), eq(false), anyString(), anyString(), any());
    }

    @Test
    void burst_429면_무벌점_반납하고_이번_드레인_사이클을_멈춘다() throws Exception {
        when(aiSummaryClient.generate(any()))
                .thenThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, RateLimitInfo.empty()));

        boolean continueDraining = worker.processOne();

        org.assertj.core.api.Assertions.assertThat(continueDraining)
                .as("burst 는 전역 게이트 포화 신호 — 이번 드레인 사이클을 멈춘다")
                .isFalse();
        verify(lifecycleService).releaseWithoutPenalty(eq(1L), anyString());
        verify(lifecycleService, never()).recordFailure(anyLong(), anyString(), anyBoolean(), anyString(), anyString(), any());
    }

    @Test
    void quota_429면_재시도_가능_실패로_기록하고_이번_드레인_사이클을_멈춘다() throws Exception {
        when(aiSummaryClient.generate(any()))
                .thenThrow(new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED, RateLimitInfo.empty()));

        boolean continueDraining = worker.processOne();

        org.assertj.core.api.Assertions.assertThat(continueDraining).isFalse();
        verify(lifecycleService).recordFailure(eq(1L), anyString(), eq(true), anyString(), any(), any());
        verify(lifecycleService, never()).releaseWithoutPenalty(anyLong(), anyString());
    }

    @Test
    void burst_가_계속_나도_같은_job_을_즉시_재선점하지_않고_드레인을_멈춘다() throws Exception {
        // claimOne 이 계속 같은 job 을 돌려주고 generate 가 매번 burst 여도, 멈추지 않으면 무한 루프가 된다.
        // processUntilEmpty 가 끝난다는 것(claimOne 1회)이 타이트 재선점 루프가 사라졌다는 증거.
        when(aiSummaryClient.generate(any()))
                .thenThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, RateLimitInfo.empty()));

        worker.processUntilEmpty();

        verify(lifecycleService, org.mockito.Mockito.times(1)).claimOne(anyString());
        verify(lifecycleService, org.mockito.Mockito.times(1)).releaseWithoutPenalty(eq(1L), anyString());
    }

    @Test
    void 비일시_4xx면_재시도_불가_실패로_기록한다() throws Exception {
        when(aiSummaryClient.generate(any())).thenThrow(new NonTransientAiException("400 bad"));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(1L), anyString(), eq(false), anyString(), any(), any());
    }

    @Test
    void 일시_5xx면_재시도_가능_실패로_기록한다() throws Exception {
        when(aiSummaryClient.generate(any())).thenThrow(new TransientAiException("503 down"));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(1L), anyString(), eq(true), any(), any(), any());
    }
}
