package com.readum.domain.user.userbook.dto;

public record UserBookCreateCommand(
        String userSessionId,
        String bookExternalId
) {
}
