package com.readum.infrastructure.ai.openai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

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
}
