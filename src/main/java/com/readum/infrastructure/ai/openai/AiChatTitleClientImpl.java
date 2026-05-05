package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.out.AiChatTitleClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatTitleClientImpl implements AiChatTitleClient {

    private final ChatClient chatClient;

    @Value("classpath:prompts/title-generator-system.st")
    private Resource systemPromptResource;

    private String systemPrompt;

    @PostConstruct
    void init() throws IOException {
        // ChatClient 의 defaultSystem 은 reading-assistant 용으로 고정돼 있어,
        // 제목 생성 호출 시에는 .system() 오버라이드로 프롬프트를 교체한다.
        // 매 호출마다 파일을 읽지 않도록 startup 시 1번만 로드해 캐시.
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public String generate(String firstUserMessage) {
        String raw = chatClient.prompt()
                .system(systemPrompt)
                .user(firstUserMessage)
                .call()
                .content();
        return raw == null ? "" : raw.strip();
    }
}
