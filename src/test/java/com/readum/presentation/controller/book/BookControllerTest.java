package com.readum.presentation.controller.book;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.service.BookSearchService;
import com.readum.presentation.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookControllerTest {

    @Mock
    private BookSearchService bookSearchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BookController controller = new BookController(bookSearchService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 정상_요청시_200과_검색_결과를_반환한다() throws Exception {
        BookSearchResult result = new BookSearchResult(
                List.of(new BookResult(
                        "https://image.aladin.co.kr/cover.jpg",
                        "리액트를 다루는 기술",
                        "김민준",
                        "길벗",
                        2024,
                        "9788966262281"
                )),
                1, 1, 30, false
        );
        given(bookSearchService.search(any())).willReturn(result);

        mockMvc.perform(get("/api/v1/books").param("keyword", "리액트"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.books[0].title").value("리액트를 다루는 기술"))
                .andExpect(jsonPath("$.data.books[0].publishedYear").value(2024))
                .andExpect(jsonPath("$.data.totalResultCount").value(1));
    }

    @Test
    void 검색어가_2자_미만이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("keyword", "리"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("2자 이상")));
    }

    @Test
    void 검색어가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void size가_50_초과면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/books")
                        .param("keyword", "리액트")
                        .param("size", "51"))
                .andExpect(status().isBadRequest());
    }
}
