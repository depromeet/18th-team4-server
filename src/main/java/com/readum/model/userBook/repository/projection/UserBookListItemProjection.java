package com.readum.model.userBook.repository.projection;

public record UserBookListItemProjection(
        Long userBookId,
        Long bookId,
        String title,
        String publisher,
        Integer publishedYear,
        String coverUrl,
        Long chatSessionCount
) {
}
