package com.readum.domain.summary.dto;

public record SummaryHistoryListCommand(
        Long userId,
        int page
) {
}
