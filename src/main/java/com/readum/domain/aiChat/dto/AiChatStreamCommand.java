package com.readum.domain.aiChat.dto;

import java.util.List;

public record AiChatStreamCommand(List<HistoryMessage> history, BookContext bookContext) {

    public record BookContext(String title, String authors, String publisher) {}
}
