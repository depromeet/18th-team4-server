package com.readum.domain.aiChat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AI 채팅의 비동기 실행 지점(생성 단계, 커밋 후 리스너)이 공유하는 가상 스레드 executor.
 * 가상 스레드는 희소 자원이 아니므로 격벽(전용 풀 분리)이 필요 없다 —
 * 제목 생성 전용 boundedElastic 격벽(구 AiChatSchedulerConfig)을 이것 하나로 대체했다.
 */
@Configuration
public class AiChatExecutorConfig {

    @Bean
    public Executor aiChatVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
