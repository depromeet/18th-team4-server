package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.MessageListResult;

import java.util.List;

public record MessageListResponse(
        List<MessageResponse> messages,
        int page,
        int size,
        boolean hasNext
) {

    public static MessageListResponse from(MessageListResult result) {
        return new MessageListResponse(
                result.messages().stream().map(MessageResponse::from).toList(),
                result.page(),
                result.size(),
                result.hasNext()
        );
    }
}
