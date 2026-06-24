package com.readum.presentation.controller.book;

import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.service.BookSearchService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.book.dto.BookSearchRequest;
import com.readum.presentation.controller.book.dto.BookSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "도서 검색", description = "외부 도서 검색 (알라딘 API 기반)")
@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookController {

    private final BookSearchService bookSearchService;

    @Operation(
            summary = "키워드 도서 검색",
            description = "알라딘 ItemSearch API 로 도서를 검색해 페이지네이션 결과를 반환한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검색 성공"),
            @ApiResponse(responseCode = "400", description = "keyword/page/size 검증 실패"),
            @ApiResponse(responseCode = "502", description = "알라딘 API 응답 오류 (5xx)"),
            @ApiResponse(responseCode = "504", description = "알라딘 API 응답 시간 초과")
    })
    @GetMapping
    public ResponseEntity<GlobalApiResponse<BookSearchResponse>> search(
            @Valid @ModelAttribute BookSearchRequest request) {
        BookSearchResult result = bookSearchService.search(request.toCommand());
        return GlobalApiResponse.ok(BookSearchResponse.from(result));
    }
}
