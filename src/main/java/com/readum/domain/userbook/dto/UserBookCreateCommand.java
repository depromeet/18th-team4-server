package com.readum.domain.userbook.dto;

public record UserBookCreateCommand(
        Long userId,
        String bookExternalId
) {
}
