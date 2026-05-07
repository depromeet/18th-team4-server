package com.readum.domain.user.userbook.dto;

import com.readum.model.user.repository.projection.UserBookListItemProjection;

public record UserBookSearchItemResult(
        Long id,
        String title,
        String publisher,
        Integer publishedYear,
        String coverUrl
) {

    public static UserBookSearchItemResult from(UserBookListItemProjection projection) {
        return new UserBookSearchItemResult(
                projection.id(),
                projection.title(),
                projection.publisher(),
                projection.publishedYear(),
                projection.coverUrl()
        );
    }
}
