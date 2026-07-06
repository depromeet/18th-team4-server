package com.readum.domain.userBook.dto;

import java.util.List;

public record UserBookSearchResult(
        List<UserBookSearchItemResult> books
) {
}
