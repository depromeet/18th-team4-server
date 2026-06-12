package com.readum.domain.aiChat.dto;

import com.readum.model.summary.entity.Summary;

public record SummaryResult(
        String title,
        String body
) {

    public static SummaryResult from(Summary summary) {
        return new SummaryResult(summary.getTitle(), summary.getBody());
    }
}
