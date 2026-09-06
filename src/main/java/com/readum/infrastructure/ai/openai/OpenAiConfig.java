package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.infrastructure.ai.openai.guardrail.ChatInputGuardrail;
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
     * 출력 검증 advisor 는 원래도 이 경로에 달지 않았다 — {@link ModerationOutputAdvisor} 는 스트림에서 조각을
     * 전부 모은 뒤 검사하므로 달면 조각 단위 전달이 성립하지 않는다. 비스트리밍 {@code chatClient} 빈에는 그대로 있다.
     *
     * <p>전송은 WebClient(reactor-netty) 이고, {@code streamUsage} 를 켜서 마지막 청크로 실측 사용량을 받는다
     * (OpenAI 의 {@code stream_options.include_usage}).
     */
    @Bean
    public OpenAiChatModel streamingChatModel(
            ResponseErrorHandler openAiResponseErrorHandler,
            @Value("${spring.ai.openai.api-key}") String apiKey,
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
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(openAiResponseErrorHandler)
                .build();
        // 재시도를 두지 않는 이유는 비스트리밍 빈과 같다 — 사용자가 기다리기를 그만둔 뒤에도 보이지 않는
        // 과금 호출이 반복되는 것을 막는다. 실패는 즉시 표면화하고 재전송 여부는 사용자가 정한다.
        RetryPolicy noRetry = RetryPolicy.builder().maxRetries(0).build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelName)
                        .streamUsage(true)
                        .build())
                .retryTemplate(new RetryTemplate(noRetry))
                .build();
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
