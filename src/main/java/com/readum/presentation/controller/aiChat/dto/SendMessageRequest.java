package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SendMessageCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        // 메시지는 AiChatErrorCode.MESSAGE_CONTENT_BLANK / MESSAGE_CONTENT_TOO_LONG 와 동일하게 통일.
        // Bean Validation 단계와 서비스 레이어 검증 (validateAndStripContent) 두 경로의 응답 메시지가
        // 일치해야 클라이언트가 같은 사유로 같은 안내를 받는다.
        @NotBlank(message = "메시지 본문은 비어 있을 수 없습니다.")
        @Size(max = 4000, message = "메시지 본문은 4000자 이하여야 합니다.")
        String content
) {

    public SendMessageCommand toCommand(Long userId, Long sessionId) {
        return new SendMessageCommand(userId, sessionId, content);
    }
}
