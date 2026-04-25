package com.readum.domain.userbook.dto;

public record UserBookCreateCommand(
        Long userId,
        String bookExternalId,
        String title,
        String authors,
        String publisher,
        Integer publishedYear,
        String coverUrl
) {
}
