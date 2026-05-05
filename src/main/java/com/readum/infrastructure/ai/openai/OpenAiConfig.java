package com.readum.infrastructure.ai.openai;

import com.readum.infrastructure.ai.openai.advisor.ModerationInputAdvisor;
import com.readum.infrastructure.ai.openai.advisor.ModerationOutputAdvisor;
import com.readum.infrastructure.ai.openai.advisor.PromptInjectionPatternAdvisor;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(GuardrailProperties.class)
public class OpenAiConfig {

    private static final int ORDER_PROMPT_INJECTION = 100;
    private static final int ORDER_SAFE_GUARD = 200;
    private static final int ORDER_MODERATION_INPUT = 300;
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
            advisors.add(new ModerationInputAdvisor(
                    moderationModel,
                    guardrailProperties.input().failureResponse(),
                    ORDER_MODERATION_INPUT
            ));
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
}
