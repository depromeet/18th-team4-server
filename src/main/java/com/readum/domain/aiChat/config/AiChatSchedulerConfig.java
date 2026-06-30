package com.readum.domain.aiChat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 제목 생성 전용 Scheduler 를 격벽(bulkhead)으로 분리하는 설정.
 *
 * 제목 생성은 첫 채팅 교환이 끝난 뒤 백그라운드로 도는 블로킹 작업(LLM 호출 + 짧은 저장)이다.
 * 영속화·채팅 응답 경로가 공유하는 전역 {@link Schedulers#boundedElastic()} 에 같이 태우면,
 * 첫 메시지가 몰릴 때 수 초짜리 제목 생성들이 그 풀(기본 10 x vCPU)을 점유해
 * 사용자 응답 직전의 짧은 영속화가 스레드를 못 받고 큐에서 대기한다(= Done 이벤트 지연).
 *
 * 전용 풀로 칸막이를 치면 제목 생성이 아무리 몰려도 자기 풀 안에서만 경합하고,
 * 영속화 풀(전역 boundedElastic)에는 손대지 않으므로 사용자 응답 latency 가 보호된다.
 */
@Slf4j
@Configuration
public class AiChatSchedulerConfig {

    // 유휴 스레드 회수 시간(초). 전역 boundedElastic 기본값과 동일하게 둬서 평소엔 스레드가 줄어들도록 한다.
    private static final int IDLE_TTL_SECONDS = 60;

    /**
     * 제목 생성 전용 boundedElastic.
     * daemon 스레드로 두어 컨텍스트 종료 시 dispose 가 누락돼도 JVM 종료를 막지 않게 한다.
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler titleGenerationScheduler(AiChatProperties properties) {
        AiChatProperties.TitleGeneration titleGeneration = properties.titleGeneration();
        log.info("[AiChat] 제목 생성 전용 스케줄러 - threadCap={}, queueCap={}",
                titleGeneration.threadCap(), titleGeneration.queueCap());
        return Schedulers.newBoundedElastic(
                titleGeneration.threadCap(),
                titleGeneration.queueCap(),
                "aichat-title-gen",
                IDLE_TTL_SECONDS,
                true);
    }
}
