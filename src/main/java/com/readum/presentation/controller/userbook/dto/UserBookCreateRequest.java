package com.readum.presentation.controller.userbook.dto;

import com.readum.domain.userbook.dto.UserBookCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "내 책장 도서 추가 요청")
public record UserBookCreateRequest(
        @Schema(description = "외부 도서 식별자 (ISBN 등)", example = "9788965700807")
        @NotBlank String bookExternalId,

        @Schema(description = "도서 제목", example = "클린 코드")
        @NotBlank String title,

        @Schema(description = "저자 (쉼표 구분 가능)", example = "로버트 C. 마틴")
        String authors,

        @Schema(description = "출판사", example = "인사이트")
        String publisher,

        @Schema(description = "출판 연도", example = "2013")
        Integer publishedYear,

        @Schema(description = "표지 이미지 URL", example = "https://example.com/cover.jpg")
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
