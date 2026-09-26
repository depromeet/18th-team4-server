package com.readum.presentation.controller.book.dto;

import com.readum.domain.book.dto.BookSearchResult;

import java.util.List;

public record BookSearchResponse(
        List<BookResponse> books,
        int totalResultCount,
        int page,
        int size,
        boolean hasNext
) {

    public static BookSearchResponse from(BookSearchResult result) {
        return new BookSearchResponse(
                result.books().stream().map(BookResponse::from).toList(),
                result.totalResultCount(),
                result.page(),
                result.size(),
                result.hasNext()
        );
    }
}
