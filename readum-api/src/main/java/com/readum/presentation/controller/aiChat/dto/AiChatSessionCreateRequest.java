package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatSessionCreateCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AiChatSessionCreateRequest(
        @NotNull(message = "userBookId는 필수입니다.")
        @Positive(message = "userBookId는 양수여야 합니다.")
        Long userBookId
) {

    public AiChatSessionCreateCommand toCommand(String userSessionId) {
        return new AiChatSessionCreateCommand(userSessionId, userBookId);
    }
}
