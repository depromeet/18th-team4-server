package com.readum.presentation.controller.ai.dto;

import com.readum.domain.ai.dto.AiChatCommand;
import jakarta.validation.constraints.NotBlank;

public record AiChatRequest(@NotBlank String message) {

    public AiChatCommand toCommand() {
        return new AiChatCommand(message);
    }
}
