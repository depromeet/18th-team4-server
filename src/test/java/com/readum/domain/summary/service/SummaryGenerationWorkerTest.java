package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.model.summary.entity.SummaryJob;
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

    @InjectMocks private SummaryGenerationWorker worker;

    @Test
    void 작업이_없으면_처리하지_않고_false를_반환한다() {
        given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(null);

        boolean processed = worker.processOne();

        assertThat(processed).isFalse();
        verify(aiSummaryClient, never()).generate(any());
    }

    @Test
    void 정상_생성되면_성공을_기록한다() {
        given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 2L, List.of()));
        SummaryDraftResult result = new SummaryDraftResult("제목", "본문");
        given(aiSummaryClient.generate(any())).willReturn(result);

        boolean processed = worker.processOne();

        assertThat(processed).isTrue();
        verify(lifecycleService).recordSuccess(eq(10L), anyString(), eq(2L), eq(result));
    }

    @Test
    void 준비단계가_null이면_생성을_건너뛴다() {
        given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString())).willReturn(null);

        worker.processOne();

        verify(aiSummaryClient, never()).generate(any());
        verify(lifecycleService, never()).recordSuccess(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void quota_소진_429는_재시도로_기록한다() {
        given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 2L, List.of()));
        given(aiSummaryClient.generate(any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED, null));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(true), anyString(), any(), isNull());
    }

    @Test
    void burst_429는_Retry_After로_재시도_시점을_설정한다() {
        given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 2L, List.of()));
        RateLimitInfo info = new RateLimitInfo(Duration.ofSeconds(30), null, null, null, null, null, null);
        given(aiSummaryClient.generate(any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, info));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(true), anyString(), any(),
                any(LocalDateTime.class));
    }

    @Test
    void 비_429_4xx는_즉시_FAILED로_기록한다() {
        given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 2L, List.of()));
        given(aiSummaryClient.generate(any())).willThrow(new NonTransientAiException("bad request"));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(false), anyString(), any(), isNull());
    }

    @Test
    void 일시적_서버_오류는_재시도로_기록한다() {
        given(lifecycleService.claimOne(eq(SummaryJob.ExecutionMode.SYNC), anyString())).willReturn(10L);
        given(lifecycleService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 2L, List.of()));
        given(aiSummaryClient.generate(any())).willThrow(new TransientAiException("server error"));

        worker.processOne();

        verify(lifecycleService).recordFailure(eq(10L), anyString(), eq(true), anyString(), any(), isNull());
    }
}
