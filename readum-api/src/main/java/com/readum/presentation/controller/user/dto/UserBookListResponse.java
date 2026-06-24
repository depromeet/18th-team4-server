package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.userbook.dto.UserBookSearchResult;

import java.util.List;

public record UserBookListResponse(
        List<UserBookListItemResponse> books
) {

    public static UserBookListResponse from(UserBookSearchResult result) {
        return new UserBookListResponse(
                result.books().stream()
                        .map(UserBookListItemResponse::from)
                        .toList()
        );
    }
}
