package com.readum.domain.aiChat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-chat")
public record AiChatProperties(
        ContextWindow contextWindow,
        MessageRule message
) {

    /**
     * 1턴 = USER 메시지 1 + ASSISTANT 메시지 1.
     */
    public record ContextWindow(int maxTurns) {

        public int maxMessages() {
            return maxTurns * 2;
        }
    }

    public record MessageRule(int maxContentLength) {
    }
}
