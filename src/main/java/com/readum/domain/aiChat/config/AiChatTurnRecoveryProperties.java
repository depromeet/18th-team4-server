package com.readum.domain.aiChat.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 기한이 지난 미종료 요청을 정리하는 <b>미정산 예약 반환</b>의 설정.
 * {@code AiChatProperties.Streaming}(한 턴의 기한)과 나눠 두는 이유는 재는 대상이 달라서다 —
 * 저쪽은 <b>한 요청</b>의 기한이고, 이쪽은 <b>훑는 작업</b>의 주기·묶음이다.
 *
 * <p><b>graceSeconds 는 0 이 정본이다.</b> 요청 행의 {@code expires_at} 은 <b>접수 시각 + (선행 처리 여유 +
 * 생성 전체 기한 + 후처리 여유)</b> = 10 + 20 + 20 = 50초로 찍힌다
 * ({@code AiChatProperties.Streaming#turnRequestExpiryTimeout()}). 한 턴이 정상적으로 끝나기까지 걸릴 수 있는
 * 구간별 상한을 그 산식이 이미 담고 있으므로, 만료로 판정한 행은 곧바로 처리한다.
 *
 * <p><b>유예를 두지 않아도 정확한 이유.</b> 정확성을 보장하는 것은 시간 여유가 아니라 행 잠금이다 —
 * 종료를 확정하는 순간 {@code AiChatTurnOutcomeWriter#expireIfOverdue} 가 요청 행을 잠그고 미종료·기한 초과를
 * 다시 확인한다. 뒤늦게 성공한 실행은 같은 잠금·확인에 걸려 저장·정산이 반영되지 않고, 반대도 마찬가지다.
 * 그래서 유예를 늘려도 정확해지지 않고 <b>예약이 사용자에게 돌아가는 시각만 늦어진다</b>.
 * 0 이 아닌 값은 요청 행의 시각과 스캔이 읽는 시각의 출처가 다른 환경(인스턴스 간 시계 차이)에서만 의미가 있다.
 *
 * <p><b>maxRowsPerRun 은 상한이 아니라 묶음 크기다.</b> 한 묶음을 처리한 뒤 묶음이 꽉 찼으면 같은 스캔이
 * 곧바로 다음 묶음을 읽는다 — 밀린 양이 많아도 한 스캔에서 비운다. 이 값이 정하는 것은 한 번에 읽어 오는
 * 행 수, 곧 한 묶음이 만드는 순간 부하다.
 *
 * <p><b>반환이 늦어지는 정도.</b> 죽은 요청의 예약이 사용자에게 돌아오기까지 최악
 * {@code expires_at + graceSeconds + scanIntervalMs} 다 — 지금 값으로 접수 후 약 1분 50초다.
 * 그 사이 사용자는 그만큼의 일일 예산을 못 쓴다.
 */
@Validated
@ConfigurationProperties(prefix = "ai-chat.turn-recovery")
public record AiChatTurnRecoveryProperties(
        @Positive long scanIntervalMs,
        @PositiveOrZero long graceSeconds,
        @Positive int maxRowsPerRun
) {

    /**
     * 이번 스캔이 쓸 만료 판정 기준 시각 — 이 시각보다 기한이 앞선 행만 만료로 확정한다.
     * 목록 조회와 행 잠금이 같은 기준을 쓰도록 한 스캔에서 한 번만 계산해 양쪽에 넘긴다.
     * {@code graceSeconds} 가 0 이면 스캔 시각 그대로다.
     */
    public LocalDateTime overdueBefore(LocalDateTime now) {
        return now.minus(Duration.ofSeconds(graceSeconds));
    }
}
