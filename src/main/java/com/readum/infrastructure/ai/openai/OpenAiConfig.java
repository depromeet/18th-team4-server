package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.infrastructure.ai.openai.advisor.ModerationOutputAdvisor;
import com.readum.infrastructure.ai.openai.advisor.PromptInjectionPatternAdvisor;
import com.readum.infrastructure.ai.openai.moderation.OpenAiInputModerationClientImpl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.client.ResponseErrorHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(GuardrailProperties.class)
public class OpenAiConfig {

    private static final int ORDER_PROMPT_INJECTION = 100;
    private static final int ORDER_SAFE_GUARD = 200;
    // 입력 moderation 은 더 이상 advisor 체인이 아니라 서비스 계층(InputModerationClient) 에서 동기 실행한다.
    // 거부 응답이 일반 토큰으로 흘러 저장 트리거를 발동하던 문제를 근본 차단하기 위함.
    private static final int ORDER_MODERATION_OUTPUT = 1000;

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            GuardrailProperties guardrailProperties,
            ObjectProvider<ModerationModel> moderationModelProvider,
            @Value("classpath:prompts/reading-assistant-system.st") Resource systemPromptResource
    ) throws IOException {
        String systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);

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

        // 3) OpenAI Moderation API 기반 입출력 advisor (외부 호출, 비용 발생).
        //    enabled=true 인데 빈이 없으면 운영자가 보안 기능이 동작 중이라고 오해할 수 있어 fail-fast.
        if (guardrailProperties.moderation().enabled()) {
            ModerationModel moderationModel = moderationModelProvider.getIfAvailable();
            if (moderationModel == null) {
                throw new IllegalStateException(
                        "readum.guardrail.moderation.enabled=true 이지만 ModerationModel 빈이 등록되어 있지 않습니다. "
                                + "spring.ai.openai.moderation 설정을 확인하거나, moderation 을 비활성화하려면 "
                                + "readum.guardrail.moderation.enabled=false 로 변경하세요."
                );
            }
            advisors.add(new ModerationOutputAdvisor(
                    moderationModel,
                    guardrailProperties.output().failureResponse(),
                    ORDER_MODERATION_OUTPUT
            ));
        }

        ChatClient.Builder chatClientBuilder = builder.defaultSystem(systemPrompt);
        if (!advisors.isEmpty()) {
            chatClientBuilder = chatClientBuilder.defaultAdvisors(advisors);
        }
        return chatClientBuilder.build();
    }

    // 입력 moderation Port 구현체.
    // - enabled=false: 항상 통과하는 no-op (dev 무키 환경 또는 명시적 비활성화).
    // - enabled=true + ModerationModel 빈 있음: OpenAiInputModerationClientImpl 등록.
    // - enabled=true + ModerationModel 빈 부재: fail-fast (운영자 설정 오류 — chatClient 빈과 동일 정책).
    @Bean
    public InputModerationClient inputModerationClient(
            GuardrailProperties guardrailProperties,
            ObjectProvider<ModerationModel> moderationModelProvider
    ) {
        if (!guardrailProperties.moderation().enabled()) {
            return (userText, bookContext) -> InputModerationResult.passed();
        }
        ModerationModel moderationModel = moderationModelProvider.getIfAvailable();
        if (moderationModel == null) {
            throw new IllegalStateException(
                    "readum.guardrail.moderation.enabled=true 이지만 ModerationModel 빈이 등록되어 있지 않습니다. "
                            + "spring.ai.openai.moderation 설정을 확인하거나, moderation 을 비활성화하려면 "
                            + "readum.guardrail.moderation.enabled=false 로 변경하세요."
            );
        }
        return new OpenAiInputModerationClientImpl(moderationModel, guardrailProperties);
    }

    // Spring AI auto-config(OpenAiChatAutoConfiguration#openAiApi) 는 ResponseErrorHandler bean 을
    // ObjectProvider#getIfAvailable 로 픽업한다. 컨텍스트에 단 하나만 있으면 OpenAiApi.Builder 로
    // 자동 주입되어 RestClient/WebClient 양쪽에 적용된다.
    //
    // 이 핸들러를 통해 OpenAI 응답의 status / 헤더 / body 를 typed 하게 보고 도메인 예외로 분류한다.
    @Bean
    public ResponseErrorHandler openAiResponseErrorHandler(ObjectMapper objectMapper) {
        return new OpenAiResponseErrorHandler(objectMapper);
    }
}
