package com.readum.presentation.controller.book;

import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.service.BookSearchService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.book.dto.BookSearchRequest;
import com.readum.presentation.controller.book.dto.BookSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookController {

    private final BookSearchService bookSearchService;

    @Operation(
            summary = "도서 검색",
            description = "키워드로 알라딘 API를 통해 도서를 검색한다. " +
                    "외부 API 5xx 응답 시 502, 응답 시간 초과 시 504를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "검색 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "도서 검색 서비스 오류 응답"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "도서 검색 서비스 응답 시간 초과"),
    })
    @GetMapping
    public ResponseEntity<ApiResponse<BookSearchResponse>> search(@Valid @ModelAttribute BookSearchRequest request) {
        BookSearchResult result = bookSearchService.search(request.toCommand());
        return ApiResponse.ok(BookSearchResponse.from(result));
    }
}
