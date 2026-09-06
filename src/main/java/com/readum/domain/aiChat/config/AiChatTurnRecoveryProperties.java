package com.readum.domain.aiChat.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 기한이 지난 미종료 요청을 정리하는 만료 복구의 설정. {@code AiChatProperties.Streaming}(한 턴의 기한)과
 * 나눠 두는 이유는 재는 대상이 달라서다 — 저쪽은 <b>한 요청</b>의 기한이고, 이쪽은 <b>훑는 작업</b>의 주기·상한이다.
 * 세 값 모두 <b>후보값</b>이며 실제 만료 발생량을 보고 조정한다.
 *
 * <p><b>graceSeconds 가 무엇을 맡는가.</b> 요청 행의 {@code expires_at} 은 <b>접수 시각 + (선행 처리 여유 +
 * 생성 전체 기한 + 후처리 여유)</b> = 60 + 120 + 60 = 240초로 찍힌다
 * ({@code AiChatProperties.Streaming#turnRequestExpiryTimeout()}). 한 턴이 정상적으로 끝나기까지 걸릴 수 있는
 * 구간별 상한은 이미 그 산식이 담고 있으므로, 여기 grace 가 다시 그 몫을 셀 필요는 없다.
 *
 * <p>그래서 이 값이 맡는 것은 <b>산식이 재지 않는 어긋남</b>뿐이다 — 요청 행의 시각과 복구가 읽는 시각이 서로 다른
 * 출처(각 인스턴스의 시계)라는 점, 그리고 스캔이 주기 사이에 밀릴 수 있다는 점이다. 후보값 30초는 그 두 가지를
 * 덮을 만큼으로 잡은 것이며, 실효 만료는 접수 + 270초가 된다.
 *
 * <p>이 값을 0 에 가깝게 줄이지 않는 이유: 산식의 구간별 상한은 <b>계산</b>이지 측정이 아니다. 실제 선행 처리·후처리
 * 소요를 재기 전까지는, 정상적으로 후처리 중인 요청을 복구가 가로채 환불하는 쪽보다 조금 늦게 돌려주는 쪽이 낫다.
 *
 * <p><b>반환이 늦어지는 정도.</b> 죽은 요청의 예약이 사용자에게 돌아오기까지 최악
 * {@code expires_at + graceSeconds + scanIntervalMs} 다 — 지금 값으로 접수 후 약 5분 30초다.
 * 그 사이 사용자는 그만큼의 일일 예산을 못 쓴다.
 */
@Validated
@ConfigurationProperties(prefix = "ai-chat.turn-recovery")
public record AiChatTurnRecoveryProperties(
        @Positive long scanIntervalMs,
        @Positive long graceSeconds,
        @Positive int maxRowsPerRun
) {

    /**
     * 이번 스캔이 쓸 만료 판정 기준 시각 — 이 시각보다 기한이 앞선 행만 만료로 확정한다.
     * 목록 조회와 행 잠금이 같은 기준을 쓰도록 한 스캔에서 한 번만 계산해 양쪽에 넘긴다.
     */
    public LocalDateTime overdueBefore(LocalDateTime now) {
        return now.minus(Duration.ofSeconds(graceSeconds));
    }
}
