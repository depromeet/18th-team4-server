package com.readum.domain.aiChat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * AI 채팅의 비동기 실행 지점이 쓰는 가상 스레드 실행기들.
 * 가상 스레드는 희소 자원이 아니므로 격벽(전용 풀 분리)이 필요 없다 —
 * 제목 생성 전용 boundedElastic 격벽(구 AiChatSchedulerConfig)을 가상 스레드로 대체했다.
 * 그래도 실행기를 셋으로 나눈 이유는 동시성 상한이 아니라 <b>종료 책임과 스레드 이름</b>이다.
 * 종료 순서가 서로 다르고(아래), 스레드 이름이 다르면 덤프에서 무엇이 매달려 있는지 바로 구분된다.
 */
@Configuration
public class AiChatExecutorConfig {

    /**
     * 커밋 후 리스너(제목 생성·요약 적재)가 쓰는 실행기. 채팅 턴의 수명 밖에서 도는 곁가지 작업이라
     * 진행 목록(AiChatInFlightTurnRegistry)의 추적 대상이 아니고, 종료도 Spring 의 빈 소멸에 맡긴다.
     */
    @Bean
    public Executor aiChatVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 연결별 SSE 전달 VT 를 실행하는 실행기 (Task 8 이 사용).
     *
     * <p>종료 책임은 {@link AiChatShutdownLifecycle} 이 진다. 종료 대기의 완료 조건에
     * <b>전달은 포함하지 않으므로</b>, 종료 절차는 이 실행기의 작업이 끝나기를 기다리지 않고
     * 신규 제출만 막는다. 아직 쓰고 있는 전달은 자기 전달 기한까지 진행하다 프로세스 종료와 함께 사라진다 —
     * 사용자가 마지막 몇 청크를 못 받는 것은 감수하고, 답변의 정본은 DB 로 남긴다.
     */
    @Bean(destroyMethod = "")
    public ExecutorService aiChatDeliveryExecutor() {
        return Executors.newThreadPerTaskExecutor(virtualThreadFactory("ai-chat-delivery-"));
    }

    /**
     * 생성이 끝난 뒤의 저장·정산 또는 실패 보상(후처리)을 실행하는 실행기 (Task 6·7 이 사용).
     *
     * <p>종료 책임은 {@link AiChatShutdownLifecycle} 이 진다. 진행 목록이 빌 때까지(또는 종료 기한까지)
     * 닫지 않으므로, <b>종료 절차가 시작된 뒤에 생성이 끝난 턴의 후처리 제출도 받는다</b>.
     *
     * <p>제출이 거절되면({@link java.util.concurrent.RejectedExecutionException}) 그 턴의 저장·정산은
     * 일어나지 않은 것이다. 호출자는 이를 삼켜서 성공으로 기록하면 안 된다 —
     * 메모리 목록만 정리하고 DB 의 미종료 예약은 복구(Task 9)에 넘긴다.
     */
    @Bean(destroyMethod = "")
    public ExecutorService aiChatPostProcessingExecutor() {
        return Executors.newThreadPerTaskExecutor(virtualThreadFactory("ai-chat-post-"));
    }

    /**
     * 위 두 실행기에 {@code destroyMethod = ""} 를 붙인 이유:
     * {@link ExecutorService} 는 {@link AutoCloseable} 이라 Spring 의 기본 소멸 메서드 추론이 {@code close()} 를 부르는데,
     * 그 기본 구현은 작업이 다 끝날 때까지(사실상 무기한) 막힌다. 빈 소멸은 SmartLifecycle 종료가 모두 끝난 뒤에
     * 일어나므로, 거기서 다시 무기한 기다리면 systemd 의 SIGKILL 유예(TimeoutStopSec=75초)를 넘긴다.
     * 그래서 종료를 기한 안에서 제어하는 {@link AiChatShutdownLifecycle} 한 곳으로 책임을 모은다.
     */
    private ThreadFactory virtualThreadFactory(String namePrefix) {
        return Thread.ofVirtual().name(namePrefix, 0).factory();
    }
}
