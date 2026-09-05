package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SendMessageCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @Schema(
                description = """
                        클라이언트가 발급하는 요청 식별자. 한 번 만든 메시지 전송에 대해 **재전송할 때도 같은 값을 그대로 보낸다** —
                        응답이 끊겨 결과를 모를 때 다시 보내도 답변을 새로 만들거나 토큰을 다시 예약·과금하지 않기 위한 값이다.
                        서버는 (사용자, requestId) 조합의 유일성을 보장하며, 같은 값이 다시 오면 진행 중·성공·실패
                        어느 상태든 409 로 거절한다. 실패한 요청을 다시 시도하려면 **새 식별자**를 발급해 보낸다.
                        서버는 매 요청마다 식별자를 새로 발급해 주지 않는다.
                        영문·숫자·하이픈·밑줄 64자 이내이며, UUID 를 권장한다.
                        """,
                example = "0f2f1c9a-9f4d-4b2b-8f0d-6e0b0e7d5a11",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "요청 식별자(requestId)는 비어 있을 수 없습니다.")
        @Size(max = 64, message = "요청 식별자(requestId)는 64자 이하여야 합니다.")
        @Pattern(
                regexp = "[A-Za-z0-9_-]+",
                message = "요청 식별자(requestId)는 영문·숫자·하이픈·밑줄만 사용할 수 있습니다.")
        String requestId,

        // 메시지는 AiChatErrorCode.MESSAGE_CONTENT_BLANK / MESSAGE_CONTENT_TOO_LONG 와 동일하게 통일.
        // Bean Validation 단계와 서비스 레이어 검증 (validateAndStripContent) 두 경로의 응답 메시지가
        // 일치해야 클라이언트가 같은 사유로 같은 안내를 받는다.
        @NotBlank(message = "메시지 본문은 비어 있을 수 없습니다.")
        @Size(max = 4000, message = "메시지 본문은 4000자 이하여야 합니다.")
        String content
) {

    public SendMessageCommand toCommand(Long userId, Long sessionId) {
        return new SendMessageCommand(userId, sessionId, requestId, content);
    }
}
