package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.summary.config.SummaryJobProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class SummarySchedulerConfig {

    /**
     * 감상문 워커 실행 풀. 동시 실행 수를 {@code summary-job.pool-size} 하나로 맞춘다.
     * core=max=poolSize 라 제출된 작업이 곧바로 실행되고, 큐(버퍼)를 두지 않아 동시 실행이 정확히
     * poolSize 다. 디스패처가 이미 runningWorkers 로 제출량을 poolSize 로 막으므로 정상 흐름에선
     * 큐가 필요 없고, 혹시 모를 초과 제출은 RejectedExecutionException 으로 디스패처가 다음 틱에 재시도한다.
     */
    @Bean
    public Executor summaryExecutor(SummaryJobProperties properties) {
        int poolSize = properties.poolSize();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("summary-");
        executor.initialize();
        return executor;
    }
}
