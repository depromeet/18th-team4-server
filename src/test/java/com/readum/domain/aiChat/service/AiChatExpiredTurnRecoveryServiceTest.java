package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatTurnRecoveryProperties;
import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.service.AiChatExpiredTurnRecoveryService.RecoveryReport;
import com.readum.domain.aiChat.service.AiChatTurnOutcomeWriter.TurnOutcomeResult;
import com.readum.model.aiChat.entity.AiChatTurnRequest;
import com.readum.model.aiChat.repository.AiChatTurnRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.readum.domain.aiChat.out.CompletedTurnStore;

/**
 * 미정산 예약 반환 스캔의 단위 테스트. 잠금·트랜잭션 계약은 {@link AiChatTurnOutcomeWriterTest} 가 실제 DB 로 확인하고,
 * 여기서는 스캔이 그 종료 경로를 <b>어떻게 부르는가</b>를 본다 — 기준 시각의 일관성, 상한, 한 행의 실패 격리,
 * 결과 집계.
 */
class AiChatExpiredTurnRecoveryServiceTest {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");
    private static final long GRACE_SECONDS = 60L;
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 9, 12, 10, 0, 0);

    /** 한 번에 읽어 오는 행 수. 테스트가 여러 묶음을 돌게 하려고 작게 둔다. */
    private static final int BATCH_SIZE = 3;

    private final AiChatTurnRequestRepository aiChatTurnRequestRepository =
            mock(AiChatTurnRequestRepository.class);
    private final AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter = mock(AiChatTurnOutcomeWriter.class);
    private final AiChatTurnRecoveryProperties recoveryProperties =
            new AiChatTurnRecoveryProperties(60_000L, GRACE_SECONDS, BATCH_SIZE);

    private final CompletedTurnStore completedTurnStore = mock(CompletedTurnStore.class);

    private final AiChatExpiredTurnRecoveryService recoveryService = new AiChatExpiredTurnRecoveryService(
            aiChatTurnRequestRepository, aiChatTurnOutcomeWriter, recoveryProperties, completedTurnStore);

    @org.junit.jupiter.api.BeforeEach
    void 기본은_되살릴_기록이_없는_상태() {
        // 이 테스트가 보는 것은 스캔의 뼈대다 — 되살리기 자체는 별도 테스트가 본다.
        given(completedTurnStore.find(anyLong())).willReturn(new CompletedTurnStore.Lookup.Absent());
    }

    @Test
    void 대상이_없으면_종료_트랜잭션을_한_번도_열지_않는다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any())).willReturn(List.of());

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report).isEqualTo(new RecoveryReport(0, 0, 0, 0, 0, 0, 0, 0));
        verify(aiChatTurnOutcomeWriter, never()).expireIfOverdue(any(), any(), anyString());
    }

    @Test
    void 대상마다_만료로_끝내고_돌려준_예약을_합산한다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(11L, 12L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(11L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(12L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 500));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report).isEqualTo(new RecoveryReport(2, 2, 800, 0, 0, 1, 0, 0));
    }

    @Test
    void 이미_종료됐거나_아직_기한_전인_행은_건너뜀으로_센다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(21L, 22L, 23L), List.of());
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(21L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.AlreadyFinished(AiChatTurnRequest.Status.SUCCEEDED, 9L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(22L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.StillRunning(AiChatTurnRequest.Status.RESERVED));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(23L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        // 늦은 성공이 먼저 끝낸 행과 기한 전 행을 만료로 세면 복구가 한 일을 부풀려 읽게 된다.
        assertThat(report).isEqualTo(new RecoveryReport(3, 1, 300, 2, 0, 1, 0, 0));
    }

    @Test
    void 한_행이_실패해도_나머지_행을_계속_처리한다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(31L, 32L, 33L), List.of());
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(31L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(32L), any(), anyString()))
                .willThrow(new CannotAcquireLockException("잠금 대기 시간 초과"));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(33L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 500));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        // 실패한 행은 미종료로 남아 다음 스캔이 다시 집는다 — 그 사이 다른 행이 막히면 안 된다.
        assertThat(report).isEqualTo(new RecoveryReport(3, 2, 800, 0, 1, 1, 0, 0));
        verify(aiChatTurnOutcomeWriter).expireIfOverdue(eq(33L), any(), anyString());
    }

    @Test
    void 목록_조회와_행_잠금이_같은_만료_기준_시각을_쓴다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any())).willReturn(List.of(41L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(any(), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));
        LocalDateTime beforeScan = LocalDateTime.now(ZONE_KST);

        recoveryService.recoverOverdueTurnRequests();
        LocalDateTime afterScan = LocalDateTime.now(ZONE_KST);

        ArgumentCaptor<LocalDateTime> listedAsOf = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiChatTurnRequestRepository).findOverdueUnfinishedIdsAfter(listedAsOf.capture(), anyLong(), any());
        ArgumentCaptor<LocalDateTime> lockedAsOf = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiChatTurnOutcomeWriter).expireIfOverdue(eq(41L), lockedAsOf.capture(), anyString());

        // 훑을 때와 끝낼 때의 기준이 다르면 "훑을 땐 대상, 잠글 땐 아님" 이 생겨 스캔이 헛돈다.
        assertThat(lockedAsOf.getValue()).isEqualTo(listedAsOf.getValue());
        // 기준 시각은 스캔 시각에서 안전 여유만큼 앞선 시점이다 — 기한 직후의 정상 후처리를 가로채지 않는다.
        assertThat(listedAsOf.getValue())
                .isAfterOrEqualTo(beforeScan.minusSeconds(GRACE_SECONDS))
                .isBeforeOrEqualTo(afterScan.minusSeconds(GRACE_SECONDS));
    }

    @Test
    void 한_번에_읽어오는_행_수는_설정한_묶음_크기다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any())).willReturn(List.of());

        recoveryService.recoverOverdueTurnRequests();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(aiChatTurnRequestRepository).findOverdueUnfinishedIdsAfter(any(), anyLong(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(BATCH_SIZE);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    void 묶음이_꽉_차면_같은_스캔이_다음_묶음을_이어_읽어_밀린_양을_비운다() {
        List<Long> fullBatch = idRange(1L, BATCH_SIZE);
        List<Long> lastBatch = List.of(9001L, 9002L);
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(fullBatch, lastBatch);
        given(aiChatTurnOutcomeWriter.expireIfOverdue(any(), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 10));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        // 묶음마다 다음 주기로 미루면 밀린 양이 분당 한 묶음씩만 돌아온다.
        assertThat(report).isEqualTo(
                new RecoveryReport(BATCH_SIZE + 2, BATCH_SIZE + 2, (BATCH_SIZE + 2) * 10, 0, 0, 2, 0, 0));
        verify(aiChatTurnRequestRepository, times(2)).findOverdueUnfinishedIdsAfter(any(), anyLong(), any());
    }

    @Test
    void 끝내지_못한_행이_뒤에_있는_행의_차례를_막지_않는다() {
        // 이 회차가 끝내지 못한 행(보관소를 읽지 못해 미룬 행 등)이 계속 첫 자리를 차지하면
        // 뒤에 있는 되살릴 수 있는 요청들이 영영 차례를 못 받는다.
        List<Long> firstBatch = idRange(1L, BATCH_SIZE);
        List<Long> secondBatch = idRange(101L, BATCH_SIZE);
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(firstBatch, secondBatch, List.of());
        given(completedTurnStore.find(anyLong())).willReturn(new CompletedTurnStore.Lookup.Unknown());

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report.deferred())
                .as("앞 묶음을 전부 미뤘어도 다음 묶음까지 훑어야 한다")
                .isEqualTo(BATCH_SIZE * 2);
        ArgumentCaptor<Long> afterId = ArgumentCaptor.forClass(Long.class);
        verify(aiChatTurnRequestRepository, times(3))
                .findOverdueUnfinishedIdsAfter(any(), afterId.capture(), any());
        assertThat(afterId.getAllValues())
                .as("앞 묶음의 마지막 id 다음부터 이어 읽는다")
                .containsExactly(0L, (long) BATCH_SIZE, 100L + BATCH_SIZE);
        verify(aiChatTurnOutcomeWriter, never()).expireIfOverdue(any(), any(), anyString());
    }


    // --- 보관된 완성본으로 되살리기 -------------------------------------------------------------------

    @Test
    void 끝까지_만들어진_답변이_남아_있으면_환불_대신_그대로_확정한다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(61L), List.of());
        given(completedTurnStore.find(61L)).willReturn(foundCompletedTurn(61L));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(), anyInt(), any()))
                .willReturn(new TurnOutcomeResult.Succeeded(
                        new AiChatTurnOutcomeWriter.SavedAssistantMessage(9L, 100, 7, 107, GENERATED_AT)));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report.recovered()).isEqualTo(1);
        assertThat(report.expired()).isZero();
        verify(aiChatTurnOutcomeWriter, never()).expireIfOverdue(any(), any(), anyString());
        // 정상 경로와 같은 트랜잭션·같은 청구 산식·같은 작성 시각을 쓴다.
        verify(aiChatTurnOutcomeWriter).finishSuccessfully(eq(61L), any(), eq(11), eq(GENERATED_AT));
        // DB 종료가 확실해진 뒤에만 기록을 지운다.
        verify(completedTurnStore).delete(61L);
    }

    @Test
    void 확정에_실패하면_환불하지_않고_기록도_지우지_않는다() {
        // 되살릴 수 있는 결과가 남아 있는데 저장만 실패한 것이다. 환불하면 답변을 우리 손으로 버린다.
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(62L), List.of());
        given(completedTurnStore.find(62L)).willReturn(foundCompletedTurn(62L));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(), anyInt(), any()))
                .willThrow(new CannotAcquireLockException("잠금 대기 시간 초과"));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.expired()).isZero();
        verify(aiChatTurnOutcomeWriter, never()).expireIfOverdue(any(), any(), anyString());
        verify(completedTurnStore, never()).delete(62L);
    }

    @Test
    void 이미_끝난_요청이면_되살리지_않고_기록만_지운다() {
        // 살아 있던 생성이 먼저 확정했다 — 저장·정산을 두 번 반영하지 않는다.
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(63L), List.of());
        given(completedTurnStore.find(63L)).willReturn(foundCompletedTurn(63L));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(), anyInt(), any()))
                .willReturn(new TurnOutcomeResult.AlreadyFinished(AiChatTurnRequest.Status.SUCCEEDED, 9L));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report.recovered()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        verify(completedTurnStore).delete(63L);
    }

    @Test
    void 보관소를_읽지_못하면_그_행을_건드리지_않고_다음_회차로_미룬다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(64L), List.of());
        given(completedTurnStore.find(64L)).willReturn(new CompletedTurnStore.Lookup.Unknown());

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report.deferred()).isEqualTo(1);
        verify(aiChatTurnOutcomeWriter, never()).expireIfOverdue(any(), any(), anyString());
        verify(aiChatTurnOutcomeWriter, never()).finishSuccessfully(anyLong(), any(), anyInt(), any());
        verify(completedTurnStore, never()).delete(64L);
    }

    @Test
    void 끝까지_가지_못한_턴은_적힌_기록이_없어_기존대로_환불한다() {
        // 부분 답변은 애초에 적히지 않는다 — 보관소가 비어 있는 것이 곧 "되살릴 것이 없다" 이고,
        // 사용자에게는 예약을 돌려준다.
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(65L), List.of());
        given(completedTurnStore.find(65L)).willReturn(new CompletedTurnStore.Lookup.Absent());
        given(aiChatTurnOutcomeWriter.expireIfOverdue(any(), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report.expired()).isEqualTo(1);
        assertThat(report.returnedTokens()).isEqualTo(300);
        verify(aiChatTurnOutcomeWriter, never()).finishSuccessfully(anyLong(), any(), anyInt(), any());
        verify(completedTurnStore).delete(65L);
    }

    @Test
    void 되살린_확정은_정상_경로와_같은_값을_쓴다() {
        // 어느 쪽이 먼저 확정하든 DB 결과가 같아야 한다 — 그래야 늦은 성공과 겹쳐도 결과가 갈리지 않는다.
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(66L), List.of());
        given(completedTurnStore.find(66L)).willReturn(foundCompletedTurn(66L));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(), anyInt(), any()))
                .willReturn(new TurnOutcomeResult.Succeeded(
                        new AiChatTurnOutcomeWriter.SavedAssistantMessage(9L, 100, 7, 107, GENERATED_AT)));

        recoveryService.recoverOverdueTurnRequests();

        ArgumentCaptor<AiChatGenerationOutcome> generation =
                ArgumentCaptor.forClass(AiChatGenerationOutcome.class);
        verify(aiChatTurnOutcomeWriter)
                .finishSuccessfully(eq(66L), generation.capture(), eq(11), eq(GENERATED_AT));
        AiChatGenerationOutcome recovered = generation.getValue();
        assertThat(recovered.isSuccess()).isTrue();
        assertThat(recovered.content()).isEqualTo("되살릴 답변");
        assertThat(recovered.finishReason()).isEqualTo("STOP");
        assertThat(recovered.inputTokens()).isEqualTo(100);
        assertThat(recovered.outputTokens())
                .as("공급자 실측 출력 토큰을 그대로 써야 청구가 정상 경로와 같아진다")
                .isEqualTo(7);
        assertThat(recovered.totalTokens()).isEqualTo(107);
    }

    @Test
    void 되살리는_사이_늦은_성공이_먼저_확정하면_환불도_이중_청구도_하지_않는다() {
        // 최종 판단은 기록을 읽은 시점이 아니라 DB 행 잠금과 종료 확인이다.
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any()))
                .willReturn(List.of(67L), List.of());
        given(completedTurnStore.find(67L)).willReturn(foundCompletedTurn(67L));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(), anyInt(), any()))
                .willReturn(new TurnOutcomeResult.AlreadyFinished(AiChatTurnRequest.Status.SUCCEEDED, 9L));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report.recovered()).as("두 번째 확정은 반영되지 않는다").isZero();
        assertThat(report.expired()).isZero();
        assertThat(report.returnedTokens()).as("이미 성공한 요청의 예약을 되돌리면 이중 환급이 된다").isZero();
        verify(aiChatTurnOutcomeWriter, never()).expireIfOverdue(any(), any(), anyString());
    }

    private static CompletedTurnStore.Lookup foundCompletedTurn(long turnRequestId) {
        return new CompletedTurnStore.Lookup.Found(new CompletedTurnStore.CompletedTurn(
                turnRequestId, 7L, 3L, 11, GENERATED_AT, "되살릴 답변", "STOP", 100, 7, 107));
    }

    private static List<Long> idRange(long firstId, int count) {
        return LongStream.range(firstId, firstId + count).boxed().toList();
    }

    @Test
    void 만료로_끝낸_행에는_복구가_정리했다는_표식을_남긴다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(any(), anyLong(), any())).willReturn(List.of(51L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(any(), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));

        recoveryService.recoverOverdueTurnRequests();

        verify(aiChatTurnOutcomeWriter).expireIfOverdue(
                eq(51L), any(), eq(AiChatExpiredTurnRecoveryService.EXPIRY_FAILURE_CODE));
    }
}
