package com.readum.domain.aiChat.dto;

/**
 * requestId 는 클라이언트가 발급하고 네트워크 재전송 때 그대로 유지하는 요청 식별자다 —
 * (userId, requestId) 유일 제약으로 중복 생성·예약·과금을 막는다.
 */
public record SendMessageCommand(
        Long userId,
        Long sessionId,
        String requestId,
        String content
) {
}
