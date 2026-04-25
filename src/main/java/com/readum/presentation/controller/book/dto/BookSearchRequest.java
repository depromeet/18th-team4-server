package com.readum.presentation.controller.book.dto;

import com.readum.domain.book.dto.BookSearchCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BookSearchRequest(
        @NotBlank
        @Size(min = 2, message = "검색어는 2자 이상이어야 합니다.")
        String keyword,

        @Min(value = 1, message = "page는 1 이상이어야 합니다.")
        Integer page,

        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 50, message = "size는 50 이하여야 합니다.")
        Integer size
) {

    public BookSearchCommand toCommand() {
        return new BookSearchCommand(
                keyword,
                page == null ? 1 : page,
                size == null ? 30 : size
        );
    }
}
