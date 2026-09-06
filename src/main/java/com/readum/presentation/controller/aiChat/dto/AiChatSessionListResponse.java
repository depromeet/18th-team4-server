package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatSessionListResult;

import java.util.List;

public record AiChatSessionListResponse(
        List<AiChatSessionResponse> sessions,
        int page,
        int size,
        boolean hasNext
) {

    public static AiChatSessionListResponse from(AiChatSessionListResult result) {
        return new AiChatSessionListResponse(
                result.sessions().stream().map(AiChatSessionResponse::from).toList(),
                result.page(),
                result.size(),
                result.hasNext()
        );
    }
}
