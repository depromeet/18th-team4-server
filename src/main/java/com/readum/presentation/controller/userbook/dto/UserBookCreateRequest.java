package com.readum.presentation.controller.userbook.dto;

import com.readum.domain.userbook.dto.UserBookCreateCommand;
import jakarta.validation.constraints.NotBlank;

public record UserBookCreateRequest(
        @NotBlank String bookExternalId,
        String title,
        String authors,
        String publisher,
        Integer publishedYear,
        String coverUrl
) {

    public UserBookCreateCommand toCommand(Long userId) {
        return new UserBookCreateCommand(
                userId,
                bookExternalId,
                title,
                authors,
                publisher,
                publishedYear,
                coverUrl
        );
    }
}
