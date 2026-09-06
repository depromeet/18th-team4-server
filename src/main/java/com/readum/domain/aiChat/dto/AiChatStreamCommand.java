package com.readum.domain.aiChat.dto;

import java.util.List;

public record AiChatStreamCommand(
        Long conversationId,
        List<HistoryMessage> history,
        BookContext bookContext,
        String contextSummary
) {

    public record BookContext(String title, String authors, String publisher) {}
}
