package com.readum.presentation.controller.ai.dto;

import com.readum.domain.ai.dto.AiChatResult;

public record AiChatResponse(String answer) {

    public static AiChatResponse from(AiChatResult result) {
        return new AiChatResponse(result.answer());
    }
}
