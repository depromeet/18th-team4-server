package com.readum.domain.user.userbook.dto;

import com.readum.model.user.repository.projection.UserBookListItemProjection;

public record UserBookSearchItemResult(
        Long userBookId,
        Long bookId,
        String title,
        String publisher,
        Integer publishedYear,
        String coverUrl,
        long chatSessionCount
) {

    public static UserBookSearchItemResult from(UserBookListItemProjection projection) {
        return new UserBookSearchItemResult(
                projection.userBookId(),
                projection.bookId(),
                projection.title(),
                projection.publisher(),
                projection.publishedYear(),
                projection.coverUrl(),
                projection.chatSessionCount()
        );
    }
}
