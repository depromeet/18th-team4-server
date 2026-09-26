package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.infrastructure.ai.openai.guardrail.ChatInputGuardrail;
import com.readum.infrastructure.ai.openai.guardrail.GuardrailProperties;
import com.readum.infrastructure.ai.openai.guardrail.ModerationOutputAdvisor;
import com.readum.infrastructure.ai.openai.guardrail.PromptInjectionPatternAdvisor;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.infrastructure.ai.openai.availability.AiProviderCallGuard;
import com.readum.infrastructure.ai.openai.availability.ProtectedChatModel;
import com.readum.infrastructure.ai.openai.moderation.OpenAiInputModerationClientImpl;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProjectProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(GuardrailProperties.class)
public class OpenAiConfig {

    private static final int ORDER_PROMPT_INJECTION = 100;
    private static final int ORDER_SAFE_GUARD = 200;
    private static final int ORDER_MODERATION_OUTPUT = 1000;

    // 생성이 오래 걸려도 여기서 상한을 건다 — 기존 call 경로는 read 타임아웃이 없어 무한 대기 위험이 있었다.
    private static final Duration CHAT_READ_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CHAT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 스트리밍 전용 연결 풀에서 빈 연결을 기다리는 상한. reactor-netty 기본값은 45초인데, 그만큼 기다리면
     * 초과 요청이 우리 상한의 503 대신 45초짜리 침묵으로 나타난다. 거절은 진행 중 턴 상한이 먼저 하고,
     * 풀 대기는 순간적인 몰림만 흡수하는 보조 역할이라 짧게 둔다.
     */
    private static final Duration STREAMING_PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);

    /** 스트리밍 전용 연결 풀 이름 — 지표·로그에서 다른 WebClient 사용자와 섞이지 않게 붙인다. */
    private static final String STREAMING_CONNECTION_POOL_NAME = "openai-streaming";

    /**
     * 감상문 생성({@code AiSummaryClientImpl})이 쓰는 ChatClient. 채팅 경로는 이 빈을 쓰지 않는다 —
     * 채팅은 {@link #streamingChatModel} 을 직접 부르고, 로컬 입력 검사는 {@link ChatInputGuardrail} 이 한다.
     * 그래서 여기 달린 advisor 세 개(정규식 패턴 · 금칙어 · 출력 moderation)가 적용되는 경로도 감상문 생성뿐이다.
     */
    @Bean
    public ChatClient chatClient(
            GuardrailProperties guardrailProperties,
            ObjectProvider<ModerationModel> moderationModelProvider,
            OpenAiResponseErrorHandlerFactory errorHandlerFactory,
            AiProviderCallGuard callGuard,
            OpenAiProjectProperties projectProperties,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String chatModelName,
            @Value("classpath:prompts/reading-assistant-system.st") Resource systemPromptResource
    ) throws IOException {
        String systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);

        // 1회성 요청/응답 — moderation 빈과 동일하게 블로킹 JDK HttpClient + HTTP/1.1.
        java.net.http.HttpClient jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(CHAT_CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(CHAT_READ_TIMEOUT);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(projectProperties.apiKeyOf(OpenAiProject.SUMMARY))
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder()) // OpenAiApi 빌더 필수 인자 — call 경로에서는 사용되지 않음
                .responseErrorHandler(errorHandlerFactory.create(OpenAiProject.SUMMARY))
                .build();
        // 자체 재시도를 두지 않는다(모더레이션 빈과 동일 정책). 빌더 기본 재시도(10회·지수 백오프)는
        // read 타임아웃(ResourceAccessException)까지 재시도 대상에 포함해, 최악의 경우 SseEmitter 상한(120초)을
        // 한참 지난 뒤까지 보이지 않는 과금 호출을 반복한다(#103 교훈: 재시도는 증폭기).
        // 실패는 즉시 error 이벤트로 표면화하고, 재전송 여부는 사용자가 정한다.
        RetryPolicy noRetry = RetryPolicy.builder().maxRetries(0).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(chatModelName).build())
                .retryTemplate(new RetryTemplate(noRetry))
                .build();
        // 공급자 상태 보호는 모델 경계에 붙인다 — 이 ChatClient 를 거치는 모든 호출이 같은 보호를 받는다.
        ChatModel protectedChatModel =
                new ProtectedChatModel(chatModel, AiAvailability.Capability.SUMMARY, callGuard);

        List<Advisor> advisors = inputAdvisors(guardrailProperties);

        // 3) OpenAI Moderation API 기반 출력 advisor
        advisors.add(new ModerationOutputAdvisor(
                requireModerationModel(moderationModelProvider),
                guardrailProperties.output().failureResponse(),
                ORDER_MODERATION_OUTPUT
        ));

        ChatClient.Builder chatClientBuilder =
                ChatClient.builder(protectedChatModel).defaultSystem(systemPrompt);
        if (!advisors.isEmpty()) {
            chatClientBuilder = chatClientBuilder.defaultAdvisors(advisors);
        }
        return chatClientBuilder.build();
    }

    /**
     * 채팅 스트리밍 전용 연결 풀.
     *
     * <p>주지 않으면 {@code WebClient.builder()} 의 기본 연결이 reactor-netty 의 <b>전역 공유 풀</b>
     * ({@code HttpResources}) 을 탄다. 그 풀은 최대 연결 500 · 대기 큐 1,000 · 대기 기한 45초로 열려 있어
     * (reactor-netty 1.3.4 의 {@code TcpResources#getOrCreate} 가 {@code max(기본값, 500)} 을 쓴다)
     * 초과분이 45초짜리 대기로 조용히 쌓인다. 우리가 원하는 것은 그 반대다 — 초과는 진행 중 턴 상한이
     * 503 으로 빨리 거절하고, 풀 대기는 순간적인 몰림만 흡수하는 보조여야 한다.
     *
     * <p>그래서 최대 연결을 진행 중 턴 상한과 같게 두고(그 상한을 넘는 동시 스트림은 애초에 생기지 않는다),
     * 대기 큐는 그 1/4 로, 대기 기한은 5초로 줄인다. 이름을 붙여 다른 WebClient 사용자와 섞이지 않게 한다.
     * 값의 정본은 {@code ai-chat.streaming.max-in-flight-turns} 한 곳이라 둘이 어긋날 수 없다.
     */
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider openAiStreamingConnectionProvider(AiChatProperties aiChatProperties) {
        AiChatProperties.Streaming streaming = aiChatProperties.streaming();
        return ConnectionProvider.builder(STREAMING_CONNECTION_POOL_NAME)
                .maxConnections(streaming.maxInFlightTurns())
                .pendingAcquireMaxCount(streaming.streamingPendingAcquireMaxCount())
                .pendingAcquireTimeout(STREAMING_PENDING_ACQUIRE_TIMEOUT)
                .build();
    }

    /**
     * 채팅 응답 스트리밍 전용 ChatModel.
     *
     * <p>ChatClient 가 아니라 ChatModel 을 노출한다. ChatClient 의 스트림 경로에는 내부 advisor
     * ({@code ChatModelStreamAdvisor}) 가 자동으로 끼며, 그 advisor 는 모델 스트림 뒤에
     * {@code publishOn(Schedulers.boundedElastic())} 를 둔다. 끄거나 다른 scheduler 를 지정하는 옵션이 없고,
     * 사용자 advisor 를 하나도 달지 않아도 남는다(Spring AI 2.0.0-M4 소스 확인). 전달·저장·정산을 가상 스레드로
     * 옮긴 뒤에는 청크를 공유 풀로 한 번 더 옮겨 실을 업무상 이유가 없어 그 경계를 두지 않는다.
     * 직접 호출이 Spring AI 내부의 모든 실행 자원이나 모든 scheduler 사용을 없앤다는 뜻은 아니다.
     *
     * <p>ChatClient 가 대신 해 주던 것 중 이 경로가 쓰던 기능은 각각 이렇게 보존한다.
     * <ul>
     *   <li>시스템 메시지 + 대화 이력의 프롬프트 조립 → {@code AiChatClientImpl} 이 같은 순서로 직접 조립</li>
     *   <li>로컬 입력 검사(정규식 패턴 · 금칙어) → {@link ChatInputGuardrail} 이 같은 판정을 하되,
     *       차단은 이 스트림 안이 아니라 선행 단계에서 입력 moderation 차단과 같은 모양(400)으로 거절한다</li>
     *   <li>모델·옵션({@code streamUsage} 포함)·재시도 0회·오류 핸들러 → 이 빈에 그대로</li>
     * </ul>
     * 이 경로에는 출력 검증 advisor 를 달지 않는다 — {@link ModerationOutputAdvisor} 는 조각을 전부 모은 뒤
     * 검사하므로 달면 조각 단위 전달이 성립하지 않는다. 그 advisor 는 감상문 생성용 {@link #chatClient} 빈에 달려 있다.
     *
     * <p>전송은 WebClient(reactor-netty) 이고, {@code streamUsage} 를 켜서 마지막 청크로 실측 사용량을 받는다
     * (OpenAI 의 {@code stream_options.include_usage}). 연결 풀은 전역 공유 풀이 아니라
     * {@link #openAiStreamingConnectionProvider} 가 준 전용 풀을 쓴다.
     */
    @Bean
    public ChatModel streamingChatModel(
            ConnectionProvider openAiStreamingConnectionProvider,
            OpenAiResponseErrorHandlerFactory errorHandlerFactory,
            AiProviderCallGuard callGuard,
            AiChatProperties aiChatProperties,
            OpenAiProjectProperties projectProperties,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String chatModelName
    ) {
        // 스트리밍은 RestClient 가 아니라 WebClient 경로를 탄다. RestClient 는 OpenAiApi 빌더의
        // 필수 인자라 채팅용과 동일한 JDK 기반 구성을 넘겨두지만 스트림 호출에서는 쓰이지 않는다.
        java.net.http.HttpClient jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(CHAT_CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(CHAT_READ_TIMEOUT);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(projectProperties.apiKeyOf(OpenAiProject.CHAT))
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(
                                HttpClient.create(openAiStreamingConnectionProvider)))
                        // 오류 분류를 여기서 따로 얹는다 — OpenAiApi 는 responseErrorHandler 를 RestClient 에만
                        // 연결하므로, 이것이 없으면 스트림의 429·401·503 이 우리 분류를 거치지 않고
                        // WebClientResponseException 으로 올라가 "세지 않는 실패" 로 묻힌다.
                        .filter(errorHandlerFactory.createStreamingErrorFilter(OpenAiProject.CHAT)))
                // 스트림 경로에서는 쓰이지 않지만 빌더의 필수 인자다(RestClient 쪽에만 연결된다).
                .responseErrorHandler(errorHandlerFactory.create(OpenAiProject.CHAT))
                .build();
        // 자체 재시도를 두지 않는다 — 사용자가 기다리기를 그만둔 뒤에도 보이지 않는 과금 호출이 반복되는 것을
        // 막는다(#103 교훈: 재시도는 증폭기). 실패는 즉시 표면화하고 재전송 여부는 사용자가 정한다.
        RetryPolicy noRetry = RetryPolicy.builder().maxRetries(0).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelName)
                        .streamUsage(true)
                        .build())
                .retryTemplate(new RetryTemplate(noRetry))
                .build();
        // 공급자 상태 보호를 모델 경계에 둔다 — 스트림이 실제로 구독됐을 때 허가를 받고, 끝나는 모양을 보고 결과를 남긴다.
        // 응답 기한 둘도 여기서 건다: 보호 구간 바깥에 두면 기한 초과가 취소로만 보여 공급자 상태에 반영되지 않는다.
        AiChatProperties.Streaming streaming = aiChatProperties.streaming();
        return new ProtectedChatModel(
                chatModel,
                AiAvailability.Capability.CHAT,
                callGuard,
                Duration.ofSeconds(streaming.generationIdleTimeoutSeconds()),
                Duration.ofSeconds(streaming.generationTotalTimeoutSeconds()));
    }

    /** 스트리밍 경로가 ChatClient 없이 수행할 로컬 입력 검사 — 판정 기준은 입력 advisor 두 개와 같다. */
    @Bean
    public ChatInputGuardrail chatInputGuardrail(GuardrailProperties guardrailProperties) {
        return new ChatInputGuardrail(guardrailProperties.input());
    }

    /** 로컬 입력 검사 advisor 목록 (정규식 prompt-injection 차단 · 금칙어). 호출자가 뒤에 출력 advisor 를 덧붙일 수 있게 가변 목록을 준다. */
    private List<Advisor> inputAdvisors(GuardrailProperties guardrailProperties) {
        List<Advisor> advisors = new ArrayList<>();

        // 1) 정규식 기반 prompt-injection / jailbreak 패턴 차단 (로컬, 무료)
        if (!guardrailProperties.input().injectionPatterns().isEmpty()) {
            advisors.add(new PromptInjectionPatternAdvisor(
                    guardrailProperties.input().injectionPatterns(),
                    guardrailProperties.input().failureResponse(),
                    ORDER_PROMPT_INJECTION
            ));
        }

        // 2) Spring AI 공식 SafeGuardAdvisor — 사내 금칙어 substring 매칭
        if (!guardrailProperties.input().sensitiveWords().isEmpty()) {
            advisors.add(SafeGuardAdvisor.builder()
                    .sensitiveWords(guardrailProperties.input().sensitiveWords())
                    .failureResponse(guardrailProperties.input().failureResponse())
                    .order(ORDER_SAFE_GUARD)
                    .build());
        }
        return advisors;
    }

    @Bean
    public InputModerationClient inputModerationClient(
            GuardrailProperties guardrailProperties,
            ObjectProvider<ModerationModel> moderationModelProvider
    ) {
        return new OpenAiInputModerationClientImpl(
                requireModerationModel(moderationModelProvider), guardrailProperties);
    }

    private ModerationModel requireModerationModel(ObjectProvider<ModerationModel> moderationModelProvider) {
        ModerationModel moderationModel = moderationModelProvider.getIfAvailable();
        if (moderationModel == null) {
            throw new IllegalStateException(
                    "ModerationModel 빈이 등록되어 있지 않습니다. "
                            + "openai.projects.moderation.api-key 와 spring.ai.openai.moderation 설정을 확인하세요."
            );
        }
        return moderationModel;
    }

    // 오류 핸들러는 더 이상 컨텍스트에 하나만 두지 않는다. 핸들러가 남기는 상태(결제·잔액 쿨다운)가
    // "프로젝트 × 모델" 키에 쌓이므로, 공용 하나를 다섯 경로가 나눠 쓰면 그 키가 한 프로젝트로 고정되어
    // 제목 생성에서 본 오류가 채팅의 상태를 바꾼다. 각 경로를 조립하는 자리에서
    // OpenAiResponseErrorHandlerFactory 로 그 경로의 프로젝트에 맞는 핸들러를 만들어 붙인다.
}
