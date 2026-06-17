package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryBatchBuildItem;
import com.readum.domain.summary.dto.SummaryBatchResultItem;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.OpenAiBatch;
import com.readum.model.summary.entity.OpenAiBatchFixture;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import com.readum.model.summary.repository.OpenAiBatchRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock private OpenAiBatchRepository openAiBatchRepository;
    @Mock private SummaryJobProperties properties;

    @InjectMocks private SummaryJobLifecycleService summaryJobLifecycleService;

    @Test
    void SYNC_선점은_SYNC_PENDING만_가져온다() {
        SummaryJob syncJob = SummaryJobFixture.persistedPending(1L, 100L, LocalDateTime.now());
        given(summaryJobRepository.findClaimable(
                eq(SummaryJob.ExecutionMode.SYNC), eq(SummaryJob.Status.PENDING), any(), any()))
                .willReturn(List.of(syncJob));
        given(properties.lease()).willReturn(Duration.ofMinutes(5));

        Long claimed = summaryJobLifecycleService.claimOne(SummaryJob.ExecutionMode.SYNC, "owner-1");

        assertThat(claimed).isEqualTo(1L);
        assertThat(syncJob.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING);
    }

    @Test
    void SYNC_선점시_대상이_없으면_null을_반환한다() {
        given(summaryJobRepository.findClaimable(
                eq(SummaryJob.ExecutionMode.SYNC), eq(SummaryJob.Status.PENDING), any(), any()))
                .willReturn(List.of());

        Long claimed = summaryJobLifecycleService.claimOne(SummaryJob.ExecutionMode.SYNC, "owner-1");

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

    // ─── claimBatchChunk ──────────────────────────────────────────────────────

    @Test
    void claimBatchChunk_는_ACTIVE_세션인_BATCH_작업을_BATCH_BUILDING으로_점유하고_빌드_아이템으로_반환한다() {
        SummaryJob job = SummaryJobFixture.persistedBatchPending(20L, 1L, LocalDateTime.now());
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1L, 5L, 2, 600, "제목");
        List<AiChatMessage> messages = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, 1L, "안녕"),
                AiChatMessageFixture.persistedAssistantMessage(2L, 1L, "반갑습니다")
        );
        given(summaryJobRepository.findClaimableBatch(any(), any())).willReturn(List.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(1L)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(1L))
                .willReturn(messages);

        List<SummaryBatchBuildItem> buildItems = summaryJobLifecycleService.claimBatchChunk(
                "owner-batch", 10, Duration.ofMinutes(5));

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.BATCH_BUILDING);
        assertThat(buildItems).hasSize(1);
        SummaryBatchBuildItem item = buildItems.get(0);
        assertThat(item.jobId()).isEqualTo(20L);
        assertThat(item.sessionId()).isEqualTo(1L);
        assertThat(item.userBookId()).isEqualTo(5L);
        assertThat(item.messages()).hasSize(2);
    }

    @Test
    void claimBatchChunk_는_세션이_없으면_해당_작업을_SUCCEEDED로_처리하고_빌드_아이템에서_제외한다() {
        SummaryJob job = SummaryJobFixture.persistedBatchPending(21L, 2L, LocalDateTime.now());
        given(summaryJobRepository.findClaimableBatch(any(), any())).willReturn(List.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(2L)).willReturn(Optional.empty());

        List<SummaryBatchBuildItem> buildItems = summaryJobLifecycleService.claimBatchChunk(
                "owner-batch", 10, Duration.ofMinutes(5));

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
        assertThat(buildItems).isEmpty();
    }

    @Test
    void claimBatchChunk_는_세션이_ACTIVE가_아니면_해당_작업을_SUCCEEDED로_처리하고_빌드_아이템에서_제외한다() {
        SummaryJob job = SummaryJobFixture.persistedBatchPending(22L, 3L, LocalDateTime.now());
        AiChatSession lockedSession = AiChatSessionFixture.persistedActiveSession(3L, 7L, 2, 600, "제목");
        lockedSession.lock();
        given(summaryJobRepository.findClaimableBatch(any(), any())).willReturn(List.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(3L)).willReturn(Optional.of(lockedSession));

        List<SummaryBatchBuildItem> buildItems = summaryJobLifecycleService.claimBatchChunk(
                "owner-batch", 10, Duration.ofMinutes(5));

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
        assertThat(buildItems).isEmpty();
    }

    // ─── recordSubmission ─────────────────────────────────────────────────────

    @Test
    void recordSubmission_은_openAiBatch를_저장하고_소유권_일치_작업을_SUBMITTED로_전이한다() {
        SummaryJob job1 = SummaryJobFixture.persistedBatchBuilding(30L, 1L, "owner-s", LocalDateTime.now().plusMinutes(5));
        SummaryJob job2 = SummaryJobFixture.persistedBatchBuilding(31L, 2L, "owner-s", LocalDateTime.now().plusMinutes(5));
        OpenAiBatch savedBatch = OpenAiBatchFixture.submitted(99L, "batch_X", 2);
        given(openAiBatchRepository.save(any(OpenAiBatch.class))).willReturn(savedBatch);
        given(summaryJobRepository.findByIdForUpdate(30L)).willReturn(Optional.of(job1));
        given(summaryJobRepository.findByIdForUpdate(31L)).willReturn(Optional.of(job2));

        summaryJobLifecycleService.recordSubmission(List.of(30L, 31L), "owner-s", "batch_X", "file_in_1");

        ArgumentCaptor<OpenAiBatch> batchCaptor = ArgumentCaptor.forClass(OpenAiBatch.class);
        verify(openAiBatchRepository).save(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getJobCount()).isEqualTo(2);

        assertThat(job1.getStatus()).isEqualTo(SummaryJob.Status.SUBMITTED);
        assertThat(job1.getOpenAiBatchId()).isEqualTo(99L);
        assertThat(job2.getStatus()).isEqualTo(SummaryJob.Status.SUBMITTED);
        assertThat(job2.getOpenAiBatchId()).isEqualTo(99L);
    }

    @Test
    void recordSubmission_은_소유권_불일치_작업은_건드리지_않는다() {
        SummaryJob ownedJob = SummaryJobFixture.persistedBatchBuilding(32L, 1L, "owner-s", LocalDateTime.now().plusMinutes(5));
        SummaryJob otherJob = SummaryJobFixture.persistedBatchBuilding(33L, 2L, "other-owner", LocalDateTime.now().plusMinutes(5));
        OpenAiBatch savedBatch = OpenAiBatchFixture.submitted(100L, "batch_Y", 1);
        given(openAiBatchRepository.save(any(OpenAiBatch.class))).willReturn(savedBatch);
        given(summaryJobRepository.findByIdForUpdate(32L)).willReturn(Optional.of(ownedJob));
        given(summaryJobRepository.findByIdForUpdate(33L)).willReturn(Optional.of(otherJob));

        summaryJobLifecycleService.recordSubmission(List.of(32L, 33L), "owner-s", "batch_Y", "file_in_2");

        assertThat(ownedJob.getStatus()).isEqualTo(SummaryJob.Status.SUBMITTED);
        assertThat(otherJob.getStatus()).isEqualTo(SummaryJob.Status.BATCH_BUILDING); // 변하지 않음
        assertThat(otherJob.getOpenAiBatchId()).isNull();
    }

    // ─── releaseBuilding ──────────────────────────────────────────────────────

    @Test
    void releaseBuilding_은_소유권_일치_작업을_PENDING으로_되돌리고_attemptCount는_증가시키지_않는다() {
        SummaryJob job = SummaryJobFixture.persistedBatchBuilding(40L, 1L, "owner-r", LocalDateTime.now().plusMinutes(5));

        given(summaryJobRepository.findByIdForUpdate(40L)).willReturn(Optional.of(job));

        summaryJobLifecycleService.releaseBuilding(List.of(40L), "owner-r");

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isZero();
    }

    @Test
    void releaseBuilding_은_소유권_불일치_작업은_건드리지_않는다() {
        SummaryJob job = SummaryJobFixture.persistedBatchBuilding(41L, 1L, "other-owner", LocalDateTime.now().plusMinutes(5));

        given(summaryJobRepository.findByIdForUpdate(41L)).willReturn(Optional.of(job));

        summaryJobLifecycleService.releaseBuilding(List.of(41L), "owner-r");

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.BATCH_BUILDING); // 변하지 않음
    }

    // ─── applyBatchResult ─────────────────────────────────────────────────────

    @Test
    void applyBatchResult_는_작업이_SUBMITTED가_아니면_아무것도_하지_않는다() {
        // 준비: 이미 처리 완료(SUCCEEDED) — 재수집 시나리오. SUBMITTED 가 아니므로 건너뛰어야 한다.
        SummaryJob alreadySucceeded = SummaryJobFixture.persistedSucceeded(50L, 1L);
        given(summaryJobRepository.findByIdForUpdate(50L)).willReturn(Optional.of(alreadySucceeded));

        SummaryBatchResultItem resultItem = SummaryBatchResultItem.success(
                "summaryjob-50", new SummaryDraftResult("제목", "본문"));

        summaryJobLifecycleService.applyBatchResult(99L, resultItem);

        // 이미 완료된 작업이므로 감상문 저장·세션 잠금이 일어나지 않는다
        verify(summaryRepository, never()).save(any());
        verify(aiChatSessionRepository, never()).findByIdForUpdate(any());
        assertThat(alreadySucceeded.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
    }

    @Test
    void applyBatchResult_성공_결과는_감상문을_저장하고_세션을_잠그고_작업을_성공처리한다() {
        SummaryJob job = SummaryJobFixture.persistedSubmitted(51L, 2L, 10L);
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(2L, 7L, 2, 600, "제목");
        given(summaryJobRepository.findByIdForUpdate(51L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(2L)).willReturn(Optional.of(session));

        SummaryBatchResultItem resultItem = SummaryBatchResultItem.success(
                "summaryjob-51", new SummaryDraftResult("감상문 제목", "감상문 본문"));

        summaryJobLifecycleService.applyBatchResult(10L, resultItem);

        verify(summaryRepository).save(any(Summary.class));
        assertThat(session.isLocked()).isTrue();
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
    }

    @Test
    void applyBatchResult_재시도_가능_실패는_PENDING으로_재큐한다() {
        SummaryJob job = SummaryJobFixture.persistedSubmitted(52L, 3L, 10L);
        given(summaryJobRepository.findByIdForUpdate(52L)).willReturn(Optional.of(job));
        given(properties.maxAttempts()).willReturn(5);
        given(properties.nextAttemptFrom(any(), anyInt())).willReturn(LocalDateTime.now().plusMinutes(2));

        SummaryBatchResultItem resultItem = SummaryBatchResultItem.failure(
                "summaryjob-52", true, "BATCH_HTTP_429", "호출 허용량 초과");

        summaryJobLifecycleService.applyBatchResult(10L, resultItem);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void applyBatchResult_재시도_불가_실패는_즉시_FAILED로_처리한다() {
        SummaryJob job = SummaryJobFixture.persistedSubmitted(53L, 4L, 10L);
        given(summaryJobRepository.findByIdForUpdate(53L)).willReturn(Optional.of(job));

        // retryable=false 이므로 maxAttempts 조회 없이 즉시 FAILED
        SummaryBatchResultItem resultItem = SummaryBatchResultItem.failure(
                "summaryjob-53", false, "BATCH_HTTP_400", "잘못된 요청");

        summaryJobLifecycleService.applyBatchResult(10L, resultItem);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.FAILED);
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void applyBatchResult_는_작업이_다른_batch_소속이면_건너뛴다() {
        // 준비: openAiBatchId=99 인 SUBMITTED 작업 — batchEntityId=1 로 호출하면 소속 불일치
        SummaryJob job = SummaryJobFixture.persistedSubmitted(55L, 6L, 99L);
        given(summaryJobRepository.findByIdForUpdate(55L)).willReturn(Optional.of(job));

        SummaryBatchResultItem resultItem = SummaryBatchResultItem.success(
                "summaryjob-55", new SummaryDraftResult("제목", "본문"));

        summaryJobLifecycleService.applyBatchResult(1L, resultItem);

        // 소속 불일치 — 감상문 저장도, 상태 변경도 없어야 한다
        verify(summaryRepository, never()).save(any());
        verify(aiChatSessionRepository, never()).findByIdForUpdate(any());
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUBMITTED);
    }

    @Test
    void applyBatchResult_는_세션이_없으면_감상문_저장_없이_작업만_성공처리한다() {
        SummaryJob job = SummaryJobFixture.persistedSubmitted(54L, 5L, 10L);
        given(summaryJobRepository.findByIdForUpdate(54L)).willReturn(Optional.of(job));
        given(aiChatSessionRepository.findByIdForUpdate(5L)).willReturn(Optional.empty());

        SummaryBatchResultItem resultItem = SummaryBatchResultItem.success(
                "summaryjob-54", new SummaryDraftResult("제목", "본문"));

        summaryJobLifecycleService.applyBatchResult(10L, resultItem);

        verify(summaryRepository, never()).save(any());
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
    }

    // ─── completeBatch ────────────────────────────────────────────────────────

    @Test
    void completeBatch_는_배치를_COMPLETED로_전이한다() {
        OpenAiBatch batch = OpenAiBatchFixture.submitted(20L, "batch_Z", 2);
        given(openAiBatchRepository.findById(20L)).willReturn(Optional.of(batch));

        summaryJobLifecycleService.completeBatch(20L, "file_out", "file_err");

        assertThat(batch.getStatus()).isEqualTo(OpenAiBatch.Status.COMPLETED);
        assertThat(batch.getOutputFileId()).isEqualTo("file_out");
    }

}
