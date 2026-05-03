package com.readum.domain.aiChat.dto;

import java.util.List;

public record MessageListResult(
        List<MessageResult> messages,
        int page,
        int size,
        boolean hasNext
) {
}
