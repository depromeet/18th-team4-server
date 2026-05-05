package com.readum.domain.aiChat.dto;

import java.util.List;

public record AiChatStreamCommand(List<HistoryMessage> history) {
}
