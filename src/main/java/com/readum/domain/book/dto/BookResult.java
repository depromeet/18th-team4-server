package com.readum.domain.book.dto;

public record BookResult(
        String coverUrl,
        String title,
        String author,
        String publisher,
        Integer publishedYear,
        String isbn13
) {
}
