package com.readum.domain.user.userbook.dto;

public record UserBookCreateCommand(
        Long userId,
        String bookExternalId
) {
}
