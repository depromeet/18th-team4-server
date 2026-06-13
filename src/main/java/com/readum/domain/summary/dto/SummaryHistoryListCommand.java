package com.readum.domain.summary.dto;

public record SummaryHistoryListCommand(
        String userSessionId,
        int page
) {
}
