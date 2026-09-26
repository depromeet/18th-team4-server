package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatSessionDisplayStatus;
import com.readum.domain.aiChat.dto.AiChatSessionResult;

import java.time.LocalDate;

public record AiChatSessionResponse(
        Long sessionId,
        String title,
        AiChatSessionDisplayStatus status,
        LocalDate lastChattedDate
) {

    public static AiChatSessionResponse from(AiChatSessionResult result) {
        return new AiChatSessionResponse(
                result.sessionId(),
                result.title(),
                result.status(),
                result.lastChattedDate()
        );
    }
}
