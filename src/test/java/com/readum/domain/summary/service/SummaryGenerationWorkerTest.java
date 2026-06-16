package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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

    @Mock private SummaryJobTxService txService;
    @Mock private AiSummaryClient aiSummaryClient;

    @InjectMocks private SummaryGenerationWorker worker;

    @Test
    void 처리할_작업이_없으면_생성을_호출하지_않는다() {
        given(txService.claimOne(anyString())).willReturn(null);

        worker.drainOnce();

        verify(aiSummaryClient, never()).generate(any());
    }

    @Test
    void 정상_흐름은_준비_생성_성공기록을_순서대로_한다() {
        given(txService.claimOne(anyString())).willReturn(10L);
        given(txService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 7L, List.of()));
        given(aiSummaryClient.generate(any())).willReturn(new SummaryDraftResult("제목", "본문"));

        worker.drainOnce();

        verify(txService).recordSuccess(eq(10L), anyString(), eq(7L), any(SummaryDraftResult.class));
    }

    @Test
    void 준비단계가_null이면_생성을_건너뛴다() {
        given(txService.claimOne(anyString())).willReturn(10L);
        given(txService.prepareGeneration(eq(10L), anyString())).willReturn(null);

        worker.drainOnce();

        verify(aiSummaryClient, never()).generate(any());
        verify(txService, never()).recordSuccess(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void 생성중_TooManyRequests면_재시도가능으로_실패기록한다() {
        given(txService.claimOne(anyString())).willReturn(10L);
        given(txService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 7L, List.of()));
        given(aiSummaryClient.generate(any()))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST));

        worker.drainOnce();

        verify(txService).recordFailure(eq(10L), anyString(), eq(true), anyString(), anyString(), isNull());
    }

    @Test
    void 생성중_일반예외면_재시도가능으로_실패기록한다() {
        given(txService.claimOne(anyString())).willReturn(10L);
        given(txService.prepareGeneration(eq(10L), anyString()))
                .willReturn(new SummaryGenerationContext(1L, 7L, List.of()));
        given(aiSummaryClient.generate(any())).willThrow(new RuntimeException("boom"));

        worker.drainOnce();

        verify(txService).recordFailure(eq(10L), anyString(), eq(true), anyString(), anyString(), isNull());
    }
}
