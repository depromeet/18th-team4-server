package com.readum.domain.user.userbook.dto;

public record UserBookDeleteCommand(
        Long userId,
        Long userBookId
) {
}
