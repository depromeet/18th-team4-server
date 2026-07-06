package com.readum.domain.userBook.dto;

public record UserBookDeleteCommand(
        Long userId,
        Long userBookId
) {
}
