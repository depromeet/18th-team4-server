package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SummaryEditCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SummaryEditRequest(
        @NotBlank(message = "감상문 제목은 비어 있을 수 없습니다.")
        @Size(max = 50, message = "감상문 제목은 50자 이하여야 합니다.")
        String title,

        @NotBlank(message = "감상문 본문은 비어 있을 수 없습니다.")
        @Size(max = 2000, message = "감상문 본문은 2000자 이하여야 합니다.")
        String body
) {

    public SummaryEditCommand toCommand(Long userId, Long sessionId) {
        return new SummaryEditCommand(userId, sessionId, title, body);
    }
}
