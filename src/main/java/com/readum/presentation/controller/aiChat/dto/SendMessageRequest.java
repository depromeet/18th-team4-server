package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SendMessageCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "content는 비어 있을 수 없습니다.")
        @Size(max = 1000, message = "content는 1000자 이하여야 합니다.")
        String content
) {

    public SendMessageCommand toCommand(Long userId, Long sessionId) {
        return new SendMessageCommand(userId, sessionId, content);
    }
}
