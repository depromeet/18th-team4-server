package com.readum.domain.ai.service;

import com.readum.domain.ai.dto.AiChatCommand;
import com.readum.domain.ai.dto.AiChatResult;
import com.readum.domain.ai.out.AiChatClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiChatService {

    private final AiChatClient aiChatClient;

    public AiChatResult execute(AiChatCommand command) {
        return aiChatClient.chat(command);
    }
}
