package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.userbook.dto.UserBookCreateResult;

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
