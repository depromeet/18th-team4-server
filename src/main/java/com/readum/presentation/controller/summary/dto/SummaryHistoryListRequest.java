package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.SummaryHistoryListCommand;
import jakarta.validation.constraints.Min;

public record SummaryHistoryListRequest(
        @Min(value = 1, message = "page는 1 이상이어야 합니다.")
        int page
) {

    public SummaryHistoryListCommand toCommand(Long userId) {
        return new SummaryHistoryListCommand(userId, page);
    }
}
