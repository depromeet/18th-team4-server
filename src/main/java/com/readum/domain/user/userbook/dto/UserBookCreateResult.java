package com.readum.domain.user.userbook.dto;

import java.time.LocalDateTime;

public record UserBookCreateResult(
        Long id,
        Long userId,
        String bookExternalId,
        String title,
        String authors,
        String publisher,
        Integer publishedYear,
        String coverUrl,
        LocalDateTime createdAt
) {
}
