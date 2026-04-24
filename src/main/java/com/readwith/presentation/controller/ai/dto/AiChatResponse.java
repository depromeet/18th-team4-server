package com.readwith.presentation.controller.ai.dto;

import com.readwith.domain.ai.dto.AiChatResult;

public record AiChatResponse(String answer) {

    public static AiChatResponse from(AiChatResult result) {
        return new AiChatResponse(result.answer());
    }
}
