package com.readum.presentation.controller.aiChat.dto;

import jakarta.annotation.Nullable;

public record SummaryDraftResponse(
        String title,
        String body,
        @Nullable String quote
) {
}
