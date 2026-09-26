package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.summary.service.SummaryJobBulkEnqueuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 자동 적재 회차의 순서 계약. 무엇을 이미 훑었는지를 잃지 않는 것이 이 스케줄러의 핵심이라,
 * 기준점을 언제 심고 언제 전진시키는지가 곧 정확성이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SummarySchedulerTest {

    @Mock private SummaryJobBulkEnqueuer summaryJobBulkEnqueuer;
    @Mock private AiAvailability aiAvailability;
    @Mock private SummaryScanWatermark scanWatermark;

    @InjectMocks private SummaryScheduler summaryScheduler;

    private void givenWatermark(LocalDateTime since) {
        given(scanWatermark.initializeOrRead(any())).willReturn(since);
    }

    @Test
    void 기준점을_가용_확인보다_먼저_심는다() {
        // 나중에 심으면 첫 회차가 장애로 거절될 때 표식이 없는 채로 끝나고,
        // 다음 회차가 다시 "최근 24시간" 으로 좁혀 그 사이 구간을 통째로 잃는다.
        givenWatermark(LocalDateTime.now().minusHours(24));
        given(summaryJobBulkEnqueuer.enqueueEligibleSessions(any(), any())).willReturn(3);

        summaryScheduler.enqueueDailySummaryJobs();

        InOrder order = inOrder(scanWatermark, aiAvailability);
        order.verify(scanWatermark).initializeOrRead(any());
        order.verify(aiAvailability).requireAvailable(AiAvailability.Capability.SUMMARY);
    }

    @Test
    void 공급자가_막혀_거절되면_기준점을_전진시키지_않는다() {
        // 전진시키면 이번 회차가 훑지 않은 구간을 "이미 훑었다" 고 기록하는 셈이라 대상이 사라진다.
        givenWatermark(LocalDateTime.now().minusHours(24));
        willThrow(new AiDependencyUnavailableException(AiChatErrorCode.AI_PROVIDER_UNAVAILABLE))
                .given(aiAvailability).requireAvailable(AiAvailability.Capability.SUMMARY);

        summaryScheduler.enqueueDailySummaryJobs();

        verify(scanWatermark, never()).advanceTo(any());
        verify(summaryJobBulkEnqueuer, never()).enqueueEligibleSessions(any(), any());
    }

    @Test
    void 기준점을_다루지_못하면_스캔_자체를_하지_않는다() {
        // 조용히 기본 24시간으로 되돌리면 건너뛴 구간을 잃고도 정상처럼 보인다.
        willThrow(new IllegalStateException("Redis 불가"))
                .given(scanWatermark).initializeOrRead(any());

        summaryScheduler.enqueueDailySummaryJobs();

        verify(aiAvailability, never()).requireAvailable(any());
        verify(summaryJobBulkEnqueuer, never()).enqueueEligibleSessions(any(), any());
        verify(scanWatermark, never()).advanceTo(any());
    }

    @Test
    void 정상_회차는_건수_상한_없이_범위의_대상을_전부_적재한다() {
        // 차단 중에는 이 적재 자체가 일어나지 않으므로(위 테스트), "조금만 넣어 보며 떠본다" 는 회차가 없다.
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        givenWatermark(since);
        given(summaryJobBulkEnqueuer.enqueueEligibleSessions(any(), any())).willReturn(99);

        summaryScheduler.enqueueDailySummaryJobs();

        verify(summaryJobBulkEnqueuer).enqueueEligibleSessions(eq(since), any());
    }

    @Test
    void 거절된_회차의_구간은_다음_성공_회차가_같은_기준점부터_이어_담는다() {
        // 거절 회차가 기준점을 옮기지 않았으므로, 그 구간의 대상이 그대로 범위 안에 남아 있어야 한다.
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        givenWatermark(since);
        willThrow(new AiDependencyUnavailableException(AiChatErrorCode.AI_PROVIDER_UNAVAILABLE))
                .given(aiAvailability).requireAvailable(AiAvailability.Capability.SUMMARY);
        summaryScheduler.enqueueDailySummaryJobs();

        // 다음 회차는 정상으로 복귀 — 기준점이 그대로라 같은 구간을 이어 담는다.
        org.mockito.Mockito.reset(aiAvailability);
        given(summaryJobBulkEnqueuer.enqueueEligibleSessions(any(), any())).willReturn(99);
        summaryScheduler.enqueueDailySummaryJobs();

        verify(summaryJobBulkEnqueuer).enqueueEligibleSessions(eq(since), any());
        verify(scanWatermark).advanceTo(any());
    }

    @Test
    void 성공한_회차는_기준점을_전진시킨다() {
        givenWatermark(LocalDateTime.now().minusHours(24));
        given(summaryJobBulkEnqueuer.enqueueEligibleSessions(any(), any())).willReturn(2);

        summaryScheduler.enqueueDailySummaryJobs();

        verify(scanWatermark).advanceTo(any());
    }
}
