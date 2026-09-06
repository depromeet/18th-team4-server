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
 * <p><b>graceSeconds 가 왜 필요한가.</b> 요청 행의 {@code expires_at} 은 접수 시각 + (생성 전체 기한 +
 * 종료 대기 상한) = 120 + 60 = 180초로 찍힌다. 그런데 접수 시각은 <b>선행 처리 전</b>이고, 요청이 정상적으로
 * 끝나기까지는 그 뒤로도 시간이 든다.
 * <ul>
 *   <li>선행 처리 — moderation 호출의 상한이 연결 10초 + 읽기 30초라 최악 40초대다(OpenAiHttpClientConfig).
 *       여기에 이력 조회·예약·게이트·USER 저장의 DB·Redis 시간이 더 붙는다.</li>
 *   <li>생성 — 외부 호출 시작부터 최대 120초(generation-total-timeout-seconds).</li>
 *   <li>후처리 — 저장·정산·요청 종료 트랜잭션. DB 연결을 얻는 데만 HikariCP 기본 상한 30초가 걸릴 수 있고
 *       질의 시간이 더 붙는다.</li>
 * </ul>
 * 셋을 더한 최악은 대략 40 + 120 + 35 = 195초로 180초를 넘는다. 즉 {@code expires_at} 만으로 판정하면
 * <b>정상적으로 후처리 중인 요청</b>을 복구가 가로채 환불할 수 있다. 그래서 복구는 {@code expires_at} 을
 * 넘긴 뒤에도 이만큼 더 기다린 행만 만료로 확정한다. 60초를 두면 실효 만료는 접수 + 240초가 되어
 * 위 최악 추정(195초)에 45초쯤 여유가 남는다.
 *
 * <p>여기 적은 초 단위는 설정값과 코드에서 읽은 상한을 더한 <b>계산</b>이지 측정값이 아니다.
 * 실제 선행 처리·후처리 소요는 재지 않았다. 이 여유를 {@code expires_at} 산식 자체에 넣는 편이 더 곧지만,
 * 그 산식은 요청을 접수하는 쪽(AiChatMessageSendService)에 있어 이번 작업의 대상이 아니다.
 *
 * <p><b>반환이 늦어지는 정도.</b> 죽은 요청의 예약이 사용자에게 돌아오기까지 최악
 * {@code expires_at + graceSeconds + scanIntervalMs} 다 — 지금 값으로 접수 후 약 5분이다.
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
