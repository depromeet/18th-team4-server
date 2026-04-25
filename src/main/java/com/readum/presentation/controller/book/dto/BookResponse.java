package com.readum.presentation.controller.book.dto;

import com.readum.domain.book.dto.BookResult;

public record BookResponse(
        String coverUrl,
        String title,
        String author,
        String publisher,
        Integer publishedYear,
        String isbn13
) {

    public static BookResponse from(BookResult result) {
        return new BookResponse(
                result.coverUrl(),
                result.title(),
                result.author(),
                result.publisher(),
                result.publishedYear(),
                result.isbn13()
        );
    }
}
