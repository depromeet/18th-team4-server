package com.readum.domain.userBook.dto;

public record UserBookCreateCommand(
        Long userId,
        String bookExternalId
) {
}
