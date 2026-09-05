package com.readum.domain.aiChat.config;

import com.readum.ReadumApplication;
import com.readum.domain.aiChat.service.AiChatInFlightTurnRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료 순서를 <b>실제 웹 컨텍스트를 띄워</b> 확인한다. "웹 서버와 같은 단계를 지정했으니 안전하다" 는 단정 대신,
 * 이 Spring Boot 4.0.5 / Framework 7.0.6 조합에서 실제로 어떤 순서로 멈추는지 기록해 검사한다.
 *
 * <p>확인하는 것:
 * <ol>
 *   <li>ai-chat 종료 단계가 웹 서버 graceful shutdown 빈의 단계와 같은 값인가 (Boot 가 값을 바꾸면 여기서 깨진다)</li>
 *   <li>단계가 큰 쪽부터 멈추는가 — 우리보다 낮은 단계의 빈이 멈출 때 이미 신규 수락이 차단돼 있는가</li>
 *   <li>DB 커넥션 풀이 <b>모든 lifecycle 종료가 끝난 뒤에</b> 닫히는가 (후처리가 DB 를 쓰는 동안 풀이 먼저 닫히지 않는다)</li>
 * </ol>
 *
 * <p>컨텍스트를 직접 띄우고 닫는다 — {@code @SpringBootTest} 의 공유 컨텍스트를 테스트 안에서 닫으면
 * 테스트 프레임워크가 그 컨텍스트를 다시 쓰려다 실패한다.
 */
class AiChatShutdownOrderIntegrationTest {

    private static final List<String> STOP_ORDER = new CopyOnWriteArrayList<>();

    @Test
    void 낮은_단계가_멈출_때는_신규_수락이_이미_차단되고_DB_풀은_모든_lifecycle_종료_뒤에_닫힌다() {
        STOP_ORDER.clear();

        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                ReadumApplication.class, StopOrderProbeConfig.class)
                .web(WebApplicationType.SERVLET)
                // 이 테스트는 컨텍스트를 실제로 닫으므로 ddl-auto=create-drop 의 drop 이 돈다.
                // 다른 테스트와 같은 이름의 인메모리 DB 를 쓰면 그 테이블까지 지워버리므로 전용 이름을 쓴다.
                // 명령행 인자로 넘긴다 — SpringApplicationBuilder#properties 는 우선순위가 가장 낮은
                // default properties 라서 test 의 application.yml 에 그대로 덮인다.
                .run("--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:aiChatShutdownOrder;MODE=MySQL");

        AiChatShutdownLifecycle shutdownLifecycle = context.getBean(AiChatShutdownLifecycle.class);
        AiChatInFlightTurnRegistry registry = context.getBean(AiChatInFlightTurnRegistry.class);
        HikariDataSource connectionPool = (HikariDataSource) context.getBean(DataSource.class);
        SmartLifecycle webServerGracefulShutdown =
                (SmartLifecycle) context.getBean("webServerGracefulShutdown");

        // 전용 DB 를 실제로 쓰고 있는지 먼저 확인한다 — 여기서 공용 DB 를 잡으면
        // 아래 close() 의 drop 이 다른 테스트의 테이블까지 지운다.
        assertThat(connectionPool.getJdbcUrl()).contains("aiChatShutdownOrder");

        assertThat(shutdownLifecycle.getPhase()).isEqualTo(webServerGracefulShutdown.getPhase());
        assertThat(registry.isAcceptingNewTurns()).isTrue();
        assertThat(connectionPool.isClosed()).isFalse();

        context.close();

        assertThat(STOP_ORDER).containsExactly(
                "높은단계-정지: 수락중=true DB풀열림=true",
                "낮은단계-정지: 수락중=false DB풀열림=true",
                "빈소멸");
        assertThat(registry.isAcceptingNewTurns()).isFalse();
        assertThat(connectionPool.isClosed()).isTrue();
    }

    @Configuration
    static class StopOrderProbeConfig {

        /** ai-chat 종료보다 <b>먼저</b> 멈추는 단계. 이 시점에는 아직 신규 턴을 받고 있어야 한다. */
        @Bean
        SmartLifecycle higherPhaseProbe(AiChatInFlightTurnRegistry registry, DataSource dataSource) {
            return new ProbeLifecycle(
                    AiChatShutdownLifecycle.WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE + 1,
                    "높은단계-정지", registry, dataSource);
        }

        /** ai-chat 종료보다 <b>나중에</b> 멈추는 단계. 이 시점에는 신규 수락이 차단돼 있어야 한다. */
        @Bean
        SmartLifecycle lowerPhaseProbe(AiChatInFlightTurnRegistry registry, DataSource dataSource) {
            return new ProbeLifecycle(
                    AiChatShutdownLifecycle.WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE - 1,
                    "낮은단계-정지", registry, dataSource);
        }

        /** 빈 소멸(HikariCP close 가 일어나는 단계)이 모든 lifecycle 종료 뒤인지 보기 위한 표식. */
        @Bean
        DisposableBean beanDestroyProbe() {
            return () -> STOP_ORDER.add("빈소멸");
        }
    }

    private record ProbeLifecycle(
            int phase,
            String label,
            AiChatInFlightTurnRegistry registry,
            DataSource dataSource
    ) implements SmartLifecycle {

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            boolean poolOpen = !((HikariDataSource) dataSource).isClosed();
            STOP_ORDER.add("%s: 수락중=%s DB풀열림=%s".formatted(label, registry.isAcceptingNewTurns(), poolOpen));
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public int getPhase() {
            return phase;
        }
    }
}
