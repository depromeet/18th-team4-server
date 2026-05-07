package com.readum.model.user.repository.projection;

public record UserBookListItemProjection(
        Long userBookId,
        Long bookId,
        String title,
        String publisher,
        Integer publishedYear,
        String coverUrl
) {
}
