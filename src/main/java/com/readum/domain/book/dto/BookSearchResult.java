package com.readum.domain.book.dto;

import java.util.List;

public record BookSearchResult(
        List<BookResult> books,
        int totalResultCount,
        int page,
        int size,
        boolean hasNext
) {
}
