package com.readum.presentation.controller.userBook.dto;

import com.readum.domain.userBook.dto.UserBookCreateResult;

import java.time.LocalDateTime;

public record UserBookResponse(
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

    public static UserBookResponse from(UserBookCreateResult result) {
        return new UserBookResponse(
                result.id(),
                result.userId(),
                result.bookExternalId(),
                result.title(),
                result.authors(),
                result.publisher(),
                result.publishedYear(),
                result.coverUrl(),
                result.createdAt()
        );
    }
}
