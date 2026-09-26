package com.readum.infrastructure.ai.openai.bench;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * bench-bridge 프로파일 전용 계측 — 리액터 스케줄러에 제출되는 작업을 클래스 이름별로 센다.
 *
 * 자기 교착의 결정적 증거가 "본문 쓰기 작업이 제출은 되었으나 실행되지 않음
 * (예: OutputStreamPublisher$$Lambda submitted=N, executed=0)" 이므로,
 * 제출 시점과 실행 시점을 각각 카운트해 그 차이를 스냅샷으로 노출한다.
 */
@Slf4j
@Component
@Profile("bench-bridge")
public class BenchScheduleHookStats {

    /** 작업 클래스 하나의 제출/실행 누적 카운트 스냅샷. */
    public record TaskCount(long submittedCount, long executedCount) {
    }

    private static final class MutableTaskCount {
        private final AtomicLong submittedCount = new AtomicLong();
        private final AtomicLong executedCount = new AtomicLong();
    }

    private final ConcurrentHashMap<String, MutableTaskCount> countsByTaskClassName = new ConcurrentHashMap<>();

    @PostConstruct
    void installScheduleHook() {
        Schedulers.onScheduleHook("bench-stats", task -> {
            MutableTaskCount taskCount = countsByTaskClassName
                    .computeIfAbsent(task.getClass().getName(), taskClassName -> new MutableTaskCount());
            taskCount.submittedCount.incrementAndGet();
            return () -> {
                taskCount.executedCount.incrementAndGet();
                task.run();
            };
        });
        log.info("[bench-bridge] reactor onScheduleHook(bench-stats) 설치 — 스케줄러 제출/실행 카운트 수집 시작");
    }

    @PreDestroy
    void removeScheduleHook() {
        Schedulers.resetOnScheduleHook("bench-stats");
    }

    /** 클래스 이름별 제출/실행 카운트 스냅샷 (이름 정렬). */
    public Map<String, TaskCount> snapshot() {
        Map<String, TaskCount> snapshot = new TreeMap<>();
        countsByTaskClassName.forEach((taskClassName, taskCount) ->
                snapshot.put(taskClassName, new TaskCount(taskCount.submittedCount.get(), taskCount.executedCount.get())));
        return snapshot;
    }
}
