package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.infrastructure.ai.openai.guardrail.GuardrailProperties;
import com.readum.infrastructure.ai.openai.guardrail.ModerationOutputAdvisor;
import com.readum.infrastructure.ai.openai.guardrail.PromptInjectionPatternAdvisor;
import com.readum.infrastructure.ai.openai.moderation.OpenAiInputModerationClientImpl;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiGateProperties;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.moderation.ModerationModel;
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
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

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

    @Bean
    public ChatClient chatClient(
            GuardrailProperties guardrailProperties,
            ObjectProvider<ModerationModel> moderationModelProvider,
            ResponseErrorHandler openAiResponseErrorHandler,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String chatModelName,
            @Value("classpath:prompts/reading-assistant-system.st") Resource systemPromptResource
    ) throws IOException {
        String systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);

        // 비스트리밍 1회성 요청/응답 — moderation 빈과 동일하게 블로킹 JDK HttpClient + HTTP/1.1.
        java.net.http.HttpClient jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(CHAT_CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(CHAT_READ_TIMEOUT);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder()) // OpenAiApi 빌더 필수 인자 — call 경로에서는 사용되지 않음
                .responseErrorHandler(openAiResponseErrorHandler)
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

        List<Advisor> advisors = inputAdvisors(guardrailProperties);

        // 3) OpenAI Moderation API 기반 출력 advisor
        advisors.add(new ModerationOutputAdvisor(
                requireModerationModel(moderationModelProvider),
                guardrailProperties.output().failureResponse(),
                ORDER_MODERATION_OUTPUT
        ));

        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel).defaultSystem(systemPrompt);
        if (!advisors.isEmpty()) {
            chatClientBuilder = chatClientBuilder.defaultAdvisors(advisors);
        }
        return chatClientBuilder.build();
    }

    /**
     * [측정용 임시 — 조건 A] 채팅 응답 스트리밍 전용 ChatClient. 측정 후 처리 별도 결정, dev 머지 금지.
     *
     * 조건 A 의 세계는 "입력 검사 → 스트리밍" 이라 출력 검증 advisor 가 없다
     * ({@link ModerationOutputAdvisor} 는 스트림 경로에서 청크를 전부 모은 뒤 검사하므로 스트리밍이 성립하지 않는다).
     * 입력 advisor(정규식 패턴 · 금칙어)는 로컬 검사라 그대로 둔다.
     * 전송은 WebClient(reactor-netty) 이고, {@code streamUsage} 를 켜서 마지막 청크로 실측 usage 를 받는다
     * (OpenAI 의 {@code stream_options.include_usage}).
     */
    @Bean
    public ChatClient streamingChatClient(
            GuardrailProperties guardrailProperties,
            ResponseErrorHandler openAiResponseErrorHandler,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String chatModelName,
            @Value("classpath:prompts/reading-assistant-system.st") Resource systemPromptResource
    ) throws IOException {
        String systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);

        // 스트리밍은 RestClient 가 아니라 WebClient 경로를 탄다. RestClient 는 OpenAiApi 빌더의
        // 필수 인자라 채팅용과 동일한 JDK 기반 구성을 넘겨두지만 스트림 호출에서는 쓰이지 않는다.
        java.net.http.HttpClient jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(CHAT_CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(CHAT_READ_TIMEOUT);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(openAiResponseErrorHandler)
                .build();
        RetryPolicy noRetry = RetryPolicy.builder().maxRetries(0).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelName)
                        .streamUsage(true)
                        .build())
                .retryTemplate(new RetryTemplate(noRetry))
                .build();

        List<Advisor> advisors = inputAdvisors(guardrailProperties);
        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel).defaultSystem(systemPrompt);
        if (!advisors.isEmpty()) {
            chatClientBuilder = chatClientBuilder.defaultAdvisors(advisors);
        }
        return chatClientBuilder.build();
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
                            + "spring.ai.openai.api-key 와 spring.ai.openai.moderation 설정을 확인하세요."
            );
        }
        return moderationModel;
    }

    // Spring AI auto-config(OpenAiChatAutoConfiguration#openAiApi) 는 ResponseErrorHandler bean 을
    // ObjectProvider#getIfAvailable 로 픽업한다. 컨텍스트에 단 하나만 있으면 OpenAiApi.Builder 로
    // 자동 주입되어 RestClient/WebClient 양쪽에 적용된다.
    //
    // 이 핸들러를 통해 OpenAI 응답의 status / 헤더 / body 를 typed 하게 보고 도메인 예외로 분류한다.
    @Bean
    public ResponseErrorHandler openAiResponseErrorHandler(
            ObjectMapper objectMapper,
            OpenAiRequestGate requestGate,
            OpenAiGateProperties gateProperties,
            @Value("${spring.ai.openai.chat.options.model}") String chatModel
    ) {
        return new OpenAiResponseErrorHandler(objectMapper, requestGate, chatModel,
                gateProperties.quotaCooldownSeconds());
    }
}
