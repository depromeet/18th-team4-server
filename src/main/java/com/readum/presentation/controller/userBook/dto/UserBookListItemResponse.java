package com.readum.presentation.controller.userBook.dto;

import com.readum.domain.userBook.dto.UserBookSearchItemResult;

public record UserBookListItemResponse(
        Long userBookId,
        Long bookId,
        String title,
        String publisher,
        Integer publishedYear,
        String coverUrl,
        long chatSessionCount
) {

    public static UserBookListItemResponse from(UserBookSearchItemResult item) {
        return new UserBookListItemResponse(
                item.userBookId(),
                item.bookId(),
                item.title(),
                item.publisher(),
                item.publishedYear(),
                item.coverUrl(),
                item.chatSessionCount()
        );
    }
}
