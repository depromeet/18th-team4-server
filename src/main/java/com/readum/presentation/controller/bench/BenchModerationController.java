package com.readum.presentation.controller.bench;

import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.infrastructure.ai.openai.bench.BenchScheduleHookStats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * bench-bridge 프로파일 전용 실험 컨트롤러 — 평상시 기동에는 존재하지 않는다.
 *
 * boundedElastic 브리지 자기 교착 재현을 위한 부하 드라이버 진입점으로,
 * 사용자 API 가 아니므로 /api/v1 경로 규약·Swagger 어노테이션·GlobalApiResponse wrapper 를 적용하지 않는다.
 * (부하 드라이버가 진단 JSON 본문만으로 결과를 분류해야 해서 wrapper·전역 예외 매핑이 오히려 방해가 된다.)
 *
 * 두 조건을 비교 측정한다.
 * - mode="bridge"  (문제 구조): 리액터 전송 브리지 기반 bench 전용 클라이언트를
 *   Mono.fromCallable(...).subscribeOn(boundedElastic).block() 으로 호출.
 * - mode="current" (개선 구조): 컨텍스트의 실제 InputModerationClient 빈(블로킹 JDK HttpClient 기반 현행 구조)을
 *   요청 스레드에서 직접 호출.
 *
 * 응답은 항상 200 — 예외는 여기서 잡아 outcome 라벨로 변환한다(GlobalExceptionHandler 로 새지 않게).
 */
@RestController
@Profile("bench-bridge")
public class BenchModerationController {

    private final InputModerationClient benchBridgeInputModerationClient;
    private final InputModerationClient inputModerationClient;
    private final BenchScheduleHookStats scheduleHookStats;

    public BenchModerationController(
            @Qualifier("benchBridgeInputModerationClient") InputModerationClient benchBridgeInputModerationClient,
            InputModerationClient inputModerationClient,
            BenchScheduleHookStats scheduleHookStats
    ) {
        this.benchBridgeInputModerationClient = benchBridgeInputModerationClient;
        this.inputModerationClient = inputModerationClient;
        this.scheduleHookStats = scheduleHookStats;
    }

    public record BenchModerationRequest(String content, String mode) {
    }

    /**
     * outcome: "SUCCESS" 또는 "최상위예외클래스/루트원인클래스".
     * status: PASSED | BLOCKED | UNAVAILABLE (실패 시 null).
     */
    public record BenchModerationResponse(String outcome, String status, long elapsedMs) {
    }

    @PostMapping("/bench/input-moderation")
    public BenchModerationResponse moderate(@RequestBody BenchModerationRequest request) {
        long startNanos = System.nanoTime();
        try {
            InputModerationResult result = switch (request.mode()) {
                case "bridge" -> Mono.fromCallable(() -> benchBridgeInputModerationClient.check(request.content(), null))
                        .subscribeOn(Schedulers.boundedElastic())
                        .block();
                case "current" -> inputModerationClient.check(request.content(), null);
                case null, default -> null;
            };
            long elapsedMs = elapsedMs(startNanos);
            if (result == null) {
                return new BenchModerationResponse("UNKNOWN_MODE", null, elapsedMs);
            }
            return new BenchModerationResponse("SUCCESS", result.status().name(), elapsedMs);
        } catch (Exception exception) {
            return new BenchModerationResponse(outcomeLabel(exception), null, elapsedMs(startNanos));
        }
    }

    @GetMapping("/bench/schedule-hook-stats")
    public Map<String, BenchScheduleHookStats.TaskCount> scheduleHookStats() {
        return scheduleHookStats.snapshot();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String outcomeLabel(Exception exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return exception.getClass().getSimpleName() + "/" + rootCause.getClass().getSimpleName();
    }
}
