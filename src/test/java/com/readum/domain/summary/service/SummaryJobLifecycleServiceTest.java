package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryJobLifecycleServiceTest {

    @Mock private SummaryJobRepository summaryJobRepository;
    @Mock private AiChatSessionRepository aiChatSessionRepository;
    @Mock private AiChatMessageRepository aiChatMessageRepository;
    @Mock private SummaryRepository summaryRepository;
    @Mock private SummaryJobProperties properties;

    @InjectMocks private SummaryJobLifecycleService summaryJobLifecycleService;

    @Test
    void 선점은_PENDING_작업을_PROCESSING으로_점유한다() {
        SummaryJob job = SummaryJobFixture.persistedPending(1L, 100L, LocalDateTime.now());
        given(summaryJobRepository.findClaimable(
                eq(SummaryJob.Status.PENDING), any(), any()))
                .willReturn(List.of(job));
        given(properties.lease()).willReturn(Duration.ofMinutes(5));

        Long claimed = summaryJobLifecycleService.claimOne("owner-1");

        assertThat(claimed).isEqualTo(1L);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING);
    }

    @Test
    void 선점시_대상이_없으면_null을_반환한다() {
        given(summaryJobRepository.findClaimable(
                eq(SummaryJob.Status.PENDING), any(), any()))
                .willReturn(List.of());

        Long claimed = summaryJobLifecycleService.claimOne("owner-1");

        assertThat(claimed).isNull();
    }

    @Test
    void recordSuccess_는_소유권_일치시_감상문저장_세션잠금_작업성공을_한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1L, 5L, 2, 600, "제목");
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(1L)).willReturn(Optional.of(session));

        summaryJobLifecycleService.recordSuccess(10L, "owner-1", 1L, new SummaryDraftResult("제목", "본문"));

        verify(summaryRepository).save(any(Summary.class));
        assertThat(session.isLocked()).isTrue();
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
    }

    @Test
    void recordSuccess_는_소유권_불일치시_아무것도_하지_않는다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "other-owner", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));

        summaryJobLifecycleService.recordSuccess(10L, "owner-1", 1L, new SummaryDraftResult("제목", "본문"));

        verify(summaryRepository, never()).save(any());
        verify(aiChatSessionRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void recordFailure_는_상한미만이면_재시도를_예약한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(properties.maxAttempts()).willReturn(5);
        given(properties.nextAttemptFrom(any(), anyInt())).willReturn(LocalDateTime.now().plusMinutes(1));

        summaryJobLifecycleService.recordFailure(10L, "owner-1", true, "AI_PROVIDER_TRANSIENT", "일시", null);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void recordFailure_는_재시도불가면_즉시_FAILED로_만든다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));

        summaryJobLifecycleService.recordFailure(10L, "owner-1", false, "AI_PROVIDER_ERROR", "회복불가", null);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.FAILED);
    }

    @Test
    void prepareGeneration_은_세션이_ACTIVE가_아니면_작업을_성공처리하고_null을_반환한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        AiChatSession locked = AiChatSessionFixture.persistedActiveSession(1L, 5L, 2, 600, "제목");
        locked.lock();
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(1L)).willReturn(Optional.of(locked));

        SummaryGenerationContext context = summaryJobLifecycleService.prepareGeneration(10L, "owner-1");

        assertThat(context).isNull();
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
    }

    @Test
    void recordFailure_는_소유권_불일치시_아무것도_하지_않는다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "other-owner", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));

        summaryJobLifecycleService.recordFailure(10L, "owner-1", true, "AI_PROVIDER_TRANSIENT", "일시", null);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING); // 변하지 않음
        assertThat(job.getAttemptCount()).isZero();
    }

    @Test
    void prepareGeneration_은_소유권_불일치시_세션을_건드리지_않고_null을_반환한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "other-owner", LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));

        var context = summaryJobLifecycleService.prepareGeneration(10L, "owner-1");

        assertThat(context).isNull();
        verify(aiChatSessionRepository, never()).findByIdForUpdate(any());
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING);
    }

    @Test
    void prepareGeneration_은_ACTIVE_세션이면_대화를_모아_컨텍스트를_반환한다() {
        SummaryJob job = SummaryJobFixture.persistedProcessing(
                10L, 1L, "owner-1", LocalDateTime.now().plusMinutes(5));
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1L, 7L, 2, 600, "제목");
        given(summaryJobRepository.findByIdForUpdate(10L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(1L)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(1L))
                .willReturn(List.of());

        SummaryGenerationContext context = summaryJobLifecycleService.prepareGeneration(10L, "owner-1");

        assertThat(context).isNotNull();
        assertThat(context.sessionId()).isEqualTo(1L);
        assertThat(context.userBookId()).isEqualTo(7L);
    }

    // ─── releaseWithoutPenalty ────────────────────────────────────────────────

    @Test
    void 무벌점_반납은_시도_횟수를_올리지_않고_PENDING_으로_되돌린다() {
        String owner = java.util.UUID.randomUUID().toString();
        SummaryJob job = SummaryJob.createPending(10L);
        job.claim(owner, java.time.LocalDateTime.now().plusMinutes(5));
        int before = job.getAttemptCount();
        given(summaryJobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(job));

        summaryJobLifecycleService.releaseWithoutPenalty(1L, owner);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isEqualTo(before);
    }

    @Test
    void 무벌점_반납은_소유권이_다르면_아무것도_하지_않는다() {
        SummaryJob job = SummaryJob.createPending(10L);
        job.claim("real-owner", java.time.LocalDateTime.now().plusMinutes(5));
        given(summaryJobRepository.findByIdForUpdate(1L)).willReturn(Optional.of(job));

        summaryJobLifecycleService.releaseWithoutPenalty(1L, "other-owner");

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING);
    }

}
