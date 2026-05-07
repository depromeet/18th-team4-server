package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.userbook.dto.UserBookSearchItemResult;

public record UserBookListItemResponse(
        Long id,
        String title,
        String publisher,
        Integer publishedYear,
        String coverUrl
) {

    public static UserBookListItemResponse from(UserBookSearchItemResult item) {
        return new UserBookListItemResponse(
                item.id(),
                item.title(),
                item.publisher(),
                item.publishedYear(),
                item.coverUrl()
        );
    }
}
