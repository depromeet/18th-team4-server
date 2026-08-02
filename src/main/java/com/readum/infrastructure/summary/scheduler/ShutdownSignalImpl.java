package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.out.ShutdownSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * 종료 이벤트를 받아 표시만 남기는 어댑터.
 *
 * <p>{@link ContextClosedEvent} 를 쓰는 이유: Spring 은 빈들을 멈추기(stop) 전에 이 이벤트를 먼저
 * 발행한다. 그래서 이미 실행 중인 워커 루프가 다음 선점을 시도하기 전에 표시를 볼 수 있다.
 * 실행 풀의 정지 표시는 새 작업이 스레드에 올라탈 때만 검사되므로 이미 돌고 있는 루프에는 닿지 않는다 —
 * 그 틈을 이 표시가 메운다.
 */
@Slf4j
@Component
public class ShutdownSignalImpl implements ShutdownSignal, ApplicationListener<ContextClosedEvent> {

    private volatile boolean shuttingDown = false;

    @Override
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown = true;
        log.info("종료 신호 감지 — 워커가 새 작업 선점을 멈추고 진행 중인 작업만 마친다");
    }
}
