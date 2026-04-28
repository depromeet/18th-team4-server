package com.readum.presentation.controller.userbook.dto;

import com.readum.domain.userbook.dto.UserBookCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "내 책장 도서 추가 요청")
public record UserBookCreateRequest(
        @Schema(description = "외부 도서 식별자 (ISBN13)", example = "9788965700807")
        @NotBlank String bookExternalId
) {

    public UserBookCreateCommand toCommand(Long userId) {
        return new UserBookCreateCommand(userId, bookExternalId);
    }
}
