package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.summary.config.SummaryJobProperties;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * summaryExecutor 의 동시 실행 수가 {@code summary-job.pool-size} 와 일치하는지 검증한다 (이슈 #92).
 *
 * <p>디스패처는 동시 처리 수를 poolSize 로 제한하려 하지만(OpenAI 동시성 한도), 풀의 core/max 가
 * poolSize 와 따로 놀면 실제 동시 실행은 core 에 묶여 의도가 깨진다. core=max=poolSize 로 파생해
 * poolSize 를 단일 기준으로 만든다.
 */
class SummarySchedulerConfigTest {

    @Test
    void summaryExecutor_의_core_와_max_는_poolSize_와_일치한다() {
        int poolSize = 7;
        SummaryJobProperties properties = new SummaryJobProperties(
                poolSize, 2000L, 60000L, 300L, 5, 60L, 1024);

        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) new SummarySchedulerConfig().summaryExecutor(properties);

        assertThat(executor.getCorePoolSize()).isEqualTo(poolSize);
        assertThat(executor.getMaxPoolSize()).isEqualTo(poolSize);
    }
}
