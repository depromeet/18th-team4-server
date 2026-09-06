package com.readum.domain.aiChat.dto;

import java.util.List;

public record AiChatSessionListResult(
        List<AiChatSessionResult> sessions,
        int page,
        int size,
        boolean hasNext
) {
}
