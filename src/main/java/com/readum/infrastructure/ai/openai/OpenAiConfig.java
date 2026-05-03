package com.readum.infrastructure.ai.openai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.client.ResponseErrorHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class OpenAiConfig {

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            @Value("classpath:prompts/reading-assistant-system.st") Resource systemPromptResource
    ) throws IOException {
        String systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        return builder.defaultSystem(systemPrompt).build();
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
