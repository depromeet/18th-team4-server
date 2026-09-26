package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatSessionListCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AiChatSessionListRequest(
        @NotNull(message = "userBookId는 필수입니다.")
        @Positive(message = "userBookId는 양수여야 합니다.")
        Long userBookId,

        @Min(value = 1, message = "page는 1 이상이어야 합니다.")
        int page,

        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 100, message = "size는 100 이하여야 합니다.")
        int size
) {

    public AiChatSessionListCommand toCommand(Long userId) {
        return new AiChatSessionListCommand(userId, userBookId, page, size);
    }
}
