package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatRequest(
        @NotBlank(message = "메시지는 비어 있을 수 없습니다.")
        @Size(max = 4000, message = "메시지는 최대 4000자까지 입력할 수 있습니다.")
        String message
) {

    public AiChatCommand toCommand() {
        return new AiChatCommand(message);
    }
}
