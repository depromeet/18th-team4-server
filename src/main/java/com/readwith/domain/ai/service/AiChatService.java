package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.dto.AiChatResult;
import com.readwith.domain.ai.out.AiChatClient;
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
