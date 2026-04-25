package com.readum.presentation.controller.book;

import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.service.BookSearchService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.book.dto.BookSearchRequest;
import com.readum.presentation.controller.book.dto.BookSearchResponse;
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

    @GetMapping
    public ResponseEntity<ApiResponse<BookSearchResponse>> search(@Valid @ModelAttribute BookSearchRequest request) {
        BookSearchResult result = bookSearchService.search(request.toCommand());
        return ApiResponse.ok(BookSearchResponse.from(result));
    }
}
