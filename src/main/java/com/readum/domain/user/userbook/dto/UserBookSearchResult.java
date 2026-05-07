package com.readum.domain.user.userbook.dto;

import java.util.List;

public record UserBookSearchResult(
        List<UserBookSearchItemResult> books
) {
}
