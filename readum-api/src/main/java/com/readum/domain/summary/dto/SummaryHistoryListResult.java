package com.readum.domain.summary.dto;

import java.util.List;

public record SummaryHistoryListResult(
        List<SummaryHistoryItemResult> summaries,
        int page,
        int size,
        boolean hasNext
) {
}
