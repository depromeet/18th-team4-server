package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.SummaryHistoryListResult;

import java.util.List;

public record SummaryHistoryListResponse(
        List<SummaryHistoryItemResponse> summaries,
        int page,
        int size,
        boolean hasNext
) {

    public static SummaryHistoryListResponse from(SummaryHistoryListResult result) {
        return new SummaryHistoryListResponse(
                result.summaries().stream().map(SummaryHistoryItemResponse::from).toList(),
                result.page(),
                result.size(),
                result.hasNext()
        );
    }
}
