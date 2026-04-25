package com.readum.infrastructure.book.aladin;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AladinItemSearchResponse(
        int totalResults,
        int startIndex,
        int itemsPerPage,
        List<Item> item
) {

    public record Item(
            String title,
            String author,
            @JsonProperty("pubDate") String pubDate,
            String publisher,
            String cover,
            String isbn13
    ) {
    }
}
