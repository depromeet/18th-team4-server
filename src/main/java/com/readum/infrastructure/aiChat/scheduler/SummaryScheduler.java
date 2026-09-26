package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.summary.service.SummaryJobBulkEnqueuer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 매일 오전 6시, 최근 대화한 세션의 감상문 생성 "작업을 적재" 한다(직접 생성하지 않음).
 * 대상 = ACTIVE + 누적 토큰 ≥ 임계값 + 마지막 채팅이 24시간 이내. (종료된 세션은 ACTIVE 가 아니라 자동 제외)
 * 실제 생성은 작업 큐 워커가 OpenAI 한도에 맞춰 분산 처리한다.
 *
 * <p>적재는 세션마다 도는 per-row 루프가 아니라 집합 단위 단일 INSERT 다 — 6시 순간 DB 부하와
 * 멀티 인스턴스 중복 스캔을 줄인다. 중복/경합 안전성은 {@code NOT EXISTS} + active_session_id
 * unique 제약 + {@code INSERT IGNORE} 가 보장한다(자세한 내용은 repository 메서드 주석).
 *
 * <p><b>자동 적재에도 신규 접수와 같은 규칙을 적용한다.</b> 공급자가 막혀 있거나 차단 뒤 쌓인 작업을 비우는 중이면
 * 이번 회차는 적재하지 않는다 — 사람이 누른 요청은 막으면서 자동 적재만 수백 건을 밀어 넣으면,
 * 복구는 끝나지 않고 대기열만 길어진다. 차단 중에는 예외가 없다 — 복구 확인은 전용 스케줄러의 일이고,
 * 이 적재가 그것을 겸하지 않는다.
 *
 * <p><b>건너뛴 회차의 대상은 다음 성공 회차가 이어 담는다.</b> 조회 조건이 "최근 24시간 안에 대화가 있은 세션" 이라
 * 그냥 건너뛰면 그 하루는 다음 회차의 범위 밖으로 빠져 영영 누락된다. 그래서 스캔 기준점을 표식으로 남기고
 * ({@link SummaryScanWatermark}), <b>성공한 회차에서만</b> 전진시킨다. 기준점은 가용 확인보다 먼저 심어,
 * 첫 회차가 장애로 거절돼도 그 지점이 남게 한다. 중복 적재는 기존 {@code NOT EXISTS} + active_session_id unique 가 그대로 막는다.
 *
 * <p>공급자 상태 확인(Redis 왕복)은 DB 트랜잭션 밖에서 한다 — 적재 트랜잭션은 쓰기 한 단계
 * ({@link SummaryJobBulkEnqueuer})에만 걸려 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryScheduler {

    /** 기본 조회 범위 — 이 시간 안에 대화가 있은 세션이 대상이다. */
    private static final int DEFAULT_LOOKBACK_HOURS = 24;

    private final SummaryJobBulkEnqueuer summaryJobBulkEnqueuer;
    private final AiAvailability aiAvailability;
    private final SummaryScanWatermark scanWatermark;

    @Scheduled(cron = "0 0 6 * * *")
    public void enqueueDailySummaryJobs() {
        LocalDateTime now = LocalDateTime.now();

        // 기준점을 가용 확인보다 <b>먼저</b> 심는다. 나중에 심으면 첫 회차가 장애로 거절될 때 표식이 없는 채로 끝나고,
        // 다음 회차가 다시 "최근 24시간" 으로 좁혀 그 사이 구간을 통째로 잃는다.
        LocalDateTime since;
        try {
            since = scanWatermark.initializeOrRead(now.minusHours(DEFAULT_LOOKBACK_HOURS));
        } catch (IllegalStateException watermarkUnavailable) {
            // 조용히 기본 24시간으로 되돌리지 않는다 — 무엇을 이미 훑었는지 모르는 채로 훑으면
            // 건너뛴 구간을 잃고도 정상처럼 보인다. 다음 회차를 기다리는 편이 낫다.
            log.error("감상문 자동 생성 작업 적재 중단 — 스캔 기준점을 다루지 못했다", watermarkUnavailable);
            return;
        }

        try {
            aiAvailability.requireAvailable(AiAvailability.Capability.SUMMARY);
        } catch (AiDependencyUnavailableException blocked) {
            log.warn("감상문 자동 생성 작업 적재 건너뜀 — 공급자를 지금 쓸 수 없다."
                    + " 기준점은 그대로 남아 다음 성공 회차가 이 구간을 이어 담는다: {}", blocked.getMessage());
            return;
        }

        int enqueued = summaryJobBulkEnqueuer.enqueueEligibleSessions(since, now);

        // 표식은 범위를 다 훑은 뒤에만 전진시킨다. 이 회차는 상한 없이 전부 적재했으므로 여기서 옮긴다.
        try {
            scanWatermark.advanceTo(now);
        } catch (IllegalStateException watermarkUnavailable) {
            // 적재는 이미 끝났다. 표식을 못 옮기면 다음 회차가 같은 구간을 다시 훑을 뿐이고,
            // 중복 적재는 NOT EXISTS + active_session_id unique 가 막는다.
            log.warn("감상문 자동 적재 표식 전진 실패 — 다음 회차가 같은 구간을 다시 훑는다", watermarkUnavailable);
        }
        log.info("감상문 자동 생성 작업 적재 완료 {}건 (조회 범위 {} ~ {})", enqueued, since, now);
    }
}
