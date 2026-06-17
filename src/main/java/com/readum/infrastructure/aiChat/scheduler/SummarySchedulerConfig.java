package com.readum.infrastructure.aiChat.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class SummarySchedulerConfig {

    @Bean
    public Executor summaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("summary-");
        executor.initialize();
        return executor;
    }

    /**
     * batch builder 전용 풀. builderConcurrency(기본 2) 만큼의 동시 builder 를 수용하도록
     * core/max 를 4로 여유있게 잡는다. 큐는 작게(10) 유지해 실행 중 계수(running)가
     * 부풀어 오르는 것을 방지한다.
     */
    @Bean
    public Executor summaryBatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("summary-batch-");
        executor.initialize();
        return executor;
    }
}
