package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.MessageListCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MessageListRequest(
        @Min(value = 1, message = "page는 1 이상이어야 합니다.")
        int page,

        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 100, message = "size는 100 이하여야 합니다.")
        int size
) {

    public MessageListCommand toCommand(String userSessionId, Long sessionId) {
        return new MessageListCommand(userSessionId, sessionId, page, size);
    }
}
