package com.readwith.presentation.controller.ai.dto;

import com.readwith.domain.ai.dto.AiChatCommand;

public record AiChatRequest(String message) {

    public AiChatCommand toCommand() {
        return new AiChatCommand(message);
    }
}
