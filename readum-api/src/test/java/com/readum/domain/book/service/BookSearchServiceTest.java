package com.readum.domain.book.service;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.dto.BookSearchCommand;
import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.out.BookSearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BookSearchServiceTest {

    @Mock
    private BookSearchClient bookSearchClient;

    @InjectMocks
    private BookSearchService bookSearchService;

    @Test
    void 도서_검색_결과를_그대로_반환한다() {
        BookSearchCommand command = new BookSearchCommand("리액트", 1, 10);
        BookSearchResult expected = new BookSearchResult(
                List.of(new BookResult(
                        "https://image.aladin.co.kr/cover.jpg",
                        "리액트를 다루는 기술",
                        "김민준",
                        "길벗",
                        2024,
                        "9788966262281"
                )),
                1,
                1,
                10,
                false
        );
        given(bookSearchClient.execute(command)).willReturn(expected);

        BookSearchResult actual = bookSearchService.search(command);

        assertThat(actual).isSameAs(expected);
    }
}
