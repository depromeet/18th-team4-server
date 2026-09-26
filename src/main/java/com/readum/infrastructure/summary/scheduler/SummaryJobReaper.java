package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.service.SummaryJobLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멈춘(고아) 작업 회수기. 두 가지를 한다.
 *
 * <ul>
 *   <li>점유 시한(lease) 만료된 PROCESSING 작업을 PENDING 으로 되돌린다 — 서버 재시작·워커 장애로
 *       작업이 영영 점유 상태에 갇히는 것을 막는다.</li>
 *   <li>접수한 지 전체 대기 한도를 넘긴 미완료 작업을 실패로 끝낸다 — 공급자가 오래 막혀 있으면
 *       작업은 시도 횟수를 쓰지 않고 계속 되돌아오므로 재시도 상한이 끝을 내 주지 못한다.
 *       그런데 활성 작업이 있는 동안 그 세션의 채팅은 잠긴다. 그래서 "언젠가는 끝난다" 를 여기서 보장한다.</li>
 * </ul>
 *
 * <p>이 회수기는 공급자 상태를 보지 않는다. 공급자가 막혀 있는 동안에도 돌아야 세션이 영구히 잠기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryJobReaper {

    private static final int BATCH_SIZE = 100;

    private final SummaryJobLifecycleService summaryJobLifecycleService;

    @Scheduled(fixedDelayString = "${summary-job.reaper-interval-ms}")
    public void reclaim() {
        int reclaimed = summaryJobLifecycleService.reclaimOrphans(BATCH_SIZE);
        if (reclaimed > 0) {
            log.warn("멈춘 감상문 작업 회수 {}건", reclaimed);
        }
        int expired = summaryJobLifecycleService.expireLongWaiting(BATCH_SIZE);
        if (expired > 0) {
            log.warn("전체 대기 한도를 넘긴 감상문 작업 종료 {}건", expired);
        }
    }
}
