package com.readum.domain.user.userbook.dto;

public record UserBookDeleteCommand(
        String userSessionId,
        Long userBookId
) {
}
