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
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(chatModelName).build())
                .build();

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
