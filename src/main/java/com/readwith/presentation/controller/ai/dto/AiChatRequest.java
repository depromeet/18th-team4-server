package com.readwith.presentation.controller.ai.dto;

import com.readwith.domain.ai.dto.AiChatCommand;
import jakarta.validation.constraints.NotBlank;

public record AiChatRequest(@NotBlank String message) {

    public AiChatCommand toCommand() {
        return new AiChatCommand(message);
    }
}
