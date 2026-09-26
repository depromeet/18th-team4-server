package com.readum.domain.book.dto;

public record BookSearchCommand(
        String keyword,
        int page,
        int size
) {
}
