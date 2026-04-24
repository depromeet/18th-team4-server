package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.dto.AiChatResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiChatService {

    private final ChatClient chatClient;

    public AiChatResult execute(AiChatCommand command) {
        String answer = chatClient.prompt()
                .user(command.message())
                .call()
                .content();
        return new AiChatResult(answer);
    }
}
