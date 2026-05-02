package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.MessageListCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MessageListRequest(
        @Min(value = 1, message = "page는 1 이상이어야 합니다.")
        Integer page,

        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 100, message = "size는 100 이하여야 합니다.")
        Integer size
) {

    public MessageListRequest {
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = 20;
        }
    }

    public MessageListCommand toCommand(Long userId, Long sessionId) {
        return new MessageListCommand(userId, sessionId, page, size);
    }
}
