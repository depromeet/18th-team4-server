package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class ContextSummarySchedulerConfig {

    /**
     * 컨텍스트 요약 워커 실행 풀. 감상문 {@code summaryExecutor} 와 같은 형태 —
     * core=max=poolSize, 큐 없음이라 동시 실행이 정확히 poolSize. 디스패처가 runningWorkers 로 제출량을 막는다.
     */
    @Bean
    public Executor contextSummaryExecutor(ContextSummaryJobProperties properties) {
        int poolSize = properties.poolSize();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ctx-summary-");
        executor.initialize();
        return executor;
    }
}
