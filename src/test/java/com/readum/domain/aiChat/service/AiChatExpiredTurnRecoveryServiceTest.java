package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatTurnRecoveryProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 만료 복구 스캔의 단위 테스트. 잠금·트랜잭션 계약은 {@link AiChatTurnOutcomeWriterTest} 가 실제 DB 로 확인하고,
 * 여기서는 스캔이 그 종료 경로를 <b>어떻게 부르는가</b>를 본다 — 기준 시각의 일관성, 상한, 한 행의 실패 격리,
 * 결과 집계.
 */
class AiChatExpiredTurnRecoveryServiceTest {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");
    private static final long GRACE_SECONDS = 60L;
    private static final int MAX_ROWS_PER_RUN = 100;

    private final AiChatTurnRequestRepository aiChatTurnRequestRepository =
            mock(AiChatTurnRequestRepository.class);
    private final AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter = mock(AiChatTurnOutcomeWriter.class);
    private final AiChatTurnRecoveryProperties recoveryProperties =
            new AiChatTurnRecoveryProperties(60_000L, GRACE_SECONDS, MAX_ROWS_PER_RUN);

    private final AiChatExpiredTurnRecoveryService recoveryService = new AiChatExpiredTurnRecoveryService(
            aiChatTurnRequestRepository, aiChatTurnOutcomeWriter, recoveryProperties);

    @Test
    void 대상이_없으면_종료_트랜잭션을_한_번도_열지_않는다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIds(any(), any())).willReturn(List.of());

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report).isEqualTo(new RecoveryReport(0, 0, 0, 0, 0));
        verify(aiChatTurnOutcomeWriter, never()).expireIfOverdue(any(), any(), anyString());
    }

    @Test
    void 대상마다_만료로_끝내고_돌려준_예약을_합산한다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIds(any(), any()))
                .willReturn(List.of(11L, 12L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(11L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(12L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 500));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        assertThat(report).isEqualTo(new RecoveryReport(2, 2, 800, 0, 0));
    }

    @Test
    void 이미_종료됐거나_아직_기한_전인_행은_건너뜀으로_센다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIds(any(), any()))
                .willReturn(List.of(21L, 22L, 23L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(21L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.AlreadyFinished(AiChatTurnRequest.Status.SUCCEEDED, 9L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(22L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.StillRunning(AiChatTurnRequest.Status.RESERVED));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(23L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        // 늦은 성공이 먼저 끝낸 행과 기한 전 행을 만료로 세면 복구가 한 일을 부풀려 읽게 된다.
        assertThat(report).isEqualTo(new RecoveryReport(3, 1, 300, 2, 0));
    }

    @Test
    void 한_행이_실패해도_나머지_행을_계속_처리한다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIds(any(), any()))
                .willReturn(List.of(31L, 32L, 33L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(31L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(32L), any(), anyString()))
                .willThrow(new CannotAcquireLockException("잠금 대기 시간 초과"));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(eq(33L), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 500));

        RecoveryReport report = recoveryService.recoverOverdueTurnRequests();

        // 실패한 행은 미종료로 남아 다음 스캔이 다시 집는다 — 그 사이 다른 행이 막히면 안 된다.
        assertThat(report).isEqualTo(new RecoveryReport(3, 2, 800, 0, 1));
        verify(aiChatTurnOutcomeWriter).expireIfOverdue(eq(33L), any(), anyString());
    }

    @Test
    void 목록_조회와_행_잠금이_같은_만료_기준_시각을_쓴다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIds(any(), any())).willReturn(List.of(41L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(any(), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));
        LocalDateTime beforeScan = LocalDateTime.now(ZONE_KST);

        recoveryService.recoverOverdueTurnRequests();
        LocalDateTime afterScan = LocalDateTime.now(ZONE_KST);

        ArgumentCaptor<LocalDateTime> listedAsOf = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiChatTurnRequestRepository).findOverdueUnfinishedIds(listedAsOf.capture(), any());
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
    void 한_스캔이_집는_행_수는_설정한_상한까지다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIds(any(), any())).willReturn(List.of());

        recoveryService.recoverOverdueTurnRequests();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(aiChatTurnRequestRepository).findOverdueUnfinishedIds(any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(MAX_ROWS_PER_RUN);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    void 만료로_끝낸_행에는_복구가_정리했다는_표식을_남긴다() {
        given(aiChatTurnRequestRepository.findOverdueUnfinishedIds(any(), any())).willReturn(List.of(51L));
        given(aiChatTurnOutcomeWriter.expireIfOverdue(any(), any(), anyString()))
                .willReturn(new TurnOutcomeResult.FinishedWithoutCharge(AiChatTurnRequest.Status.EXPIRED, 300));

        recoveryService.recoverOverdueTurnRequests();

        verify(aiChatTurnOutcomeWriter).expireIfOverdue(
                eq(51L), any(), eq(AiChatExpiredTurnRecoveryService.EXPIRY_FAILURE_CODE));
    }
}
