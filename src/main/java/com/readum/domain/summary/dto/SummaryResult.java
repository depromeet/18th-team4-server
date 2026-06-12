package com.readum.domain.summary.dto;

import com.readum.model.summary.entity.Summary;

public record SummaryResult(
        Long aiChatSessionId,
        String title,
        String body,
        String quote
) {

    public static SummaryResult from(Summary summary) {
        return new SummaryResult(
                summary.getAiChatSessionId(), summary.getTitle(), summary.getBody(), summary.getQuote());
    }
}
