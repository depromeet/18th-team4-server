package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatCommand;
import jakarta.validation.constraints.NotBlank;

public record AiChatRequest(@NotBlank String message) {

    public AiChatCommand toCommand() {
        return new AiChatCommand(message);
    }
}
