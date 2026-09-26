package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.infrastructure.ai.openai.OpenAiModelNames;
import com.readum.infrastructure.ai.openai.OpenAiResponseErrorHandlerFactory;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProjectProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiModerationModel;
import org.springframework.ai.openai.OpenAiModerationOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiModerationApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 전용 복구 확인에 쓰는 호출 경로를 구성한다.
 *
 * <p><b>왜 기존 모델 빈을 쓰지 않는가.</b> 기존 빈들은 공급자 상태 보호로 감싸여 있어
 * ({@code ProtectedChatModel} · {@code ProtectedModerationModel}) 차단 중에는 스스로 막힌다 —
 * 그것으로 확인하려 하면 첫걸음에서 거절되고 차단은 영영 풀리지 않는다. 그래서 확인 경로는 감싸지 않은
 * 모델을 따로 만든다. 확인이 한 번에 하나만 나가도록 막는 것은 임차({@code probeAdmit})이고,
 * 확인 한 건의 크기는 아래 출력 상한이 묶는다.
 *
 * <p>키는 기능이 평소 쓰는 것과 같고({@code OpenAiProjectProperties.apiKeyOf}), 모델도 같고
 * ({@code OpenAiModelNames}), 오류 핸들러도 같은 프로젝트의 것을 붙인다 — 그래야 확인의 실패가
 * 실제 호출의 실패와 같은 기준으로 분류된다. 새 키를 만들지 않고, 사용자 예산이나 작업 큐도 건드리지 않는다.
 *
 * <p><b>기한과 크기를 따로 둔다.</b> 확인은 사람이 기다리는 호출이 아니므로 기한을 짧게 두고
 * ({@code openai.availability.probe.*}), 출력 상한을 몇 토큰으로 묶어 비용과 한도 사용을 최소로 만든다.
 * 연결도 전용 풀(최대 1)을 쓴다 — 채팅 스트리밍 풀을 나눠 쓰면 확인 한 건이 사용자 자리를 하나 먹는다.
 *
 * <p>확인은 기능마다 빈 하나씩이다. 스케줄러는 그것들을 목록으로 주입받으므로, 기능이 늘 때
 * 확인을 빠뜨렸는지가 이 설정 한 곳에서 드러난다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiRecoveryProbeProperties.class)
public class AiRecoveryProbeConfig {

    /** 확인 전용 연결 풀 이름 — 지표·로그에서 사용자 트래픽과 섞이지 않게 붙인다. */
    private static final String PROBE_CONNECTION_POOL_NAME = "openai-recovery-probe";

    private final OpenAiProjectProperties projectProperties;
    private final OpenAiResponseErrorHandlerFactory errorHandlerFactory;
    private final OpenAiModelNames modelNames;
    private final AiRecoveryProbeProperties probeProperties;
    private final String baseUrl;

    public AiRecoveryProbeConfig(
            OpenAiProjectProperties projectProperties,
            OpenAiResponseErrorHandlerFactory errorHandlerFactory,
            OpenAiModelNames modelNames,
            AiRecoveryProbeProperties probeProperties,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl
    ) {
        this.projectProperties = projectProperties;
        this.errorHandlerFactory = errorHandlerFactory;
        this.modelNames = modelNames;
        this.probeProperties = probeProperties;
        this.baseUrl = baseUrl;
        log.info("[OpenAI] 복구 확인 구성 — 주기 {}ms, 연결 {}s + 읽기 {}s, 스트림 전체 {}s, 출력 상한 {} 토큰",
                probeProperties.scanIntervalMs(),
                probeProperties.connectTimeout().toSeconds(),
                probeProperties.readTimeout().toSeconds(),
                probeProperties.streamTotalTimeout().toSeconds(),
                probeProperties.maxOutputTokens());
    }

    /**
     * 확인 실행 전용 단일 스레드. 확인은 한 인스턴스에서 동시에 하나만 돈다 — 스케줄러가 기능들을 순차로
     * 돌리고, 인스턴스 사이에는 임차가 기능마다 하나만 나가므로 전체 동시 확인 수도 기능마다 1 이다.
     *
     * <p>이것을 {@code @Scheduled} 스레드와 나누는 이유: {@code spring.task.scheduling.pool.size} 가 3 이고
     * 그 위에 2초 주기 큐 디스패처 둘이 얹혀 있다. 확인이 응답을 기다리는 동안 그 스레드를 물면 큐 처리가 밀린다.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService aiRecoveryProbeExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-recovery-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider aiRecoveryProbeConnectionProvider() {
        return ConnectionProvider.builder(PROBE_CONNECTION_POOL_NAME)
                .maxConnections(1)
                .pendingAcquireMaxCount(1)
                .pendingAcquireTimeout(Duration.ofSeconds(1))
                .build();
    }

    /** 채팅 확인 — 채팅이 실제로 쓰는 스트리밍 경로로 종료 신호까지 본다. */
    @Bean
    public AiProviderRecoveryProbe chatRecoveryProbe(
            @Qualifier("aiRecoveryProbeConnectionProvider") ConnectionProvider probeConnectionProvider) {
        String model = modelNames.of(OpenAiProject.CHAT);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(projectProperties.apiKeyOf(OpenAiProject.CHAT))
                .baseUrl(baseUrl)
                // 스트림 호출은 WebClient 경로를 타지만 RestClient 도 빌더의 필수 인자다.
                .restClientBuilder(blockingRestClientBuilder())
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(
                                reactor.netty.http.client.HttpClient.create(probeConnectionProvider)))
                        // 실제 채팅 경로와 같은 분류를 쓴다 — 확인의 실패가 실제 호출의 실패와 다른 기준으로
                        // 판정되면, 확인이 성공했다고 풀어 준 차단이 곧바로 같은 자리에서 다시 열린다.
                        .filter(errorHandlerFactory.createStreamingErrorFilter(OpenAiProject.CHAT)))
                .responseErrorHandler(errorHandlerFactory.create(OpenAiProject.CHAT))
                .build();
        return new ChatStreamRecoveryProbe(
                chatModel(openAiApi, model, true), probeProperties.streamTotalTimeout());
    }

    /** 검토 확인 — 판정이 실려 오는지까지 본다. */
    @Bean
    public AiProviderRecoveryProbe moderationRecoveryProbe() {
        OpenAiModerationApi api = OpenAiModerationApi.builder()
                .apiKey(projectProperties.apiKeyOf(OpenAiProject.MODERATION))
                .baseUrl(baseUrl)
                .restClientBuilder(blockingRestClientBuilder())
                .responseErrorHandler(errorHandlerFactory.create(OpenAiProject.MODERATION))
                .build();
        String model = modelNames.of(OpenAiProject.MODERATION);
        // 모델 이름을 요청에 명시한다 — 주지 않으면 SDK 기본 모델로 나가, 확인이 실제 검토 경로와 다른
        // 모델을 두드리게 된다(그 모델이 살아 있다는 사실은 우리가 쓰는 모델의 복구를 뜻하지 않는다).
        ModerationModel moderationModel = new OpenAiModerationModel(api, new RetryTemplate(noRetry()))
                .withDefaultOptions(OpenAiModerationOptions.builder().model(model).build());
        return new ModerationRecoveryProbe(moderationModel);
    }

    @Bean
    public AiProviderRecoveryProbe summaryRecoveryProbe() {
        return callProbe(AiAvailability.Capability.SUMMARY);
    }

    @Bean
    public AiProviderRecoveryProbe contextSummaryRecoveryProbe() {
        return callProbe(AiAvailability.Capability.CONTEXT_SUMMARY);
    }

    @Bean
    public AiProviderRecoveryProbe titleRecoveryProbe() {
        return callProbe(AiAvailability.Capability.TITLE);
    }

    /** 단발 호출로 사는 기능의 확인 — 감상문·컨텍스트 요약·제목은 프로젝트 키만 다르고 호출 모양이 같다. */
    private AiProviderRecoveryProbe callProbe(AiAvailability.Capability capability) {
        OpenAiProject project = OpenAiProject.of(capability);
        String model = modelNames.of(project);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(projectProperties.apiKeyOf(project))
                .baseUrl(baseUrl)
                .restClientBuilder(blockingRestClientBuilder())
                // OpenAiApi 빌더의 필수 인자 — 단발 호출 경로에서는 쓰이지 않는다.
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(errorHandlerFactory.create(project))
                .build();
        return new ChatCallRecoveryProbe(capability, chatModel(openAiApi, model, false));
    }

    /**
     * 확인용 대화 모델. 출력 상한을 몇 토큰으로 묶는다 — 살아 있는지만 보면 되므로 긴 답을 받을 이유가 없고,
     * 그 상한이 곧 확인 한 건의 비용 상한이다. 스트리밍 확인만 사용량 청크를 켠다(채팅이 켜는 것과 같은 옵션이라
     * 확인이 실제 경로와 같은 응답 모양을 받는다).
     */
    private OpenAiChatModel chatModel(OpenAiApi openAiApi, String model, boolean streaming) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(model)
                .maxTokens(probeProperties.maxOutputTokens());
        if (streaming) {
            options = options.streamUsage(true);
        }
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
                .retryTemplate(new RetryTemplate(noRetry()))
                .build();
    }

    /**
     * 확인의 단발 호출 전송 — 블로킹 JDK HttpClient(HTTP/1.1). 다른 단발 경로와 같은 모양이고,
     * 기한만 확인용 값으로 짧게 둔다.
     */
    private RestClient.Builder blockingRestClientBuilder() {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(probeProperties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(probeProperties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * 확인은 다시 시도하지 않는다. 확인의 뜻이 "지금 이 경로가 성립하는가" 하나이므로,
     * 안에서 되풀이하면 한 번의 확인이 여러 번의 과금 호출이 되고 실패 판정도 그만큼 늦어진다.
     * 다음 확인은 차단 시간이 지난 뒤 스케줄러가 다시 한다.
     */
    private RetryPolicy noRetry() {
        return RetryPolicy.builder().maxRetries(0).build();
    }
}
