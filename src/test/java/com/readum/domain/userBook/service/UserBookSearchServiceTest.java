package com.readum.domain.userBook.service;

import com.readum.domain.userBook.dto.UserBookSearchResult;
import com.readum.model.userBook.repository.UserBookRepository;
import com.readum.model.userBook.repository.projection.UserBookListItemProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserBookSearchServiceTest {

    @Mock
    private UserBookRepository userBookRepository;

    @InjectMocks
    private UserBookSearchService userBookSearchService;

    private static final Long USER_ID = 1L;

    @Test
    void 인증된_사용자로_조회하면_등록한_도서_목록을_반환한다() {
        given(userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(USER_ID))
                .willReturn(List.of(
                        new UserBookListItemProjection(20L, 2L, "최근 등록한 책", "출판사B", 2025, "http://example.com/b.jpg", 3L),
                        new UserBookListItemProjection(10L, 1L, "이전에 등록한 책", "출판사A", 2024, "http://example.com/a.jpg", 0L)
                ));

        UserBookSearchResult result = userBookSearchService.findMyBooks(USER_ID);

        assertThat(result.books()).hasSize(2);
        assertThat(result.books().get(0).userBookId()).isEqualTo(20L);
        assertThat(result.books().get(0).bookId()).isEqualTo(2L);
        assertThat(result.books().get(0).title()).isEqualTo("최근 등록한 책");
        assertThat(result.books().get(0).publisher()).isEqualTo("출판사B");
        assertThat(result.books().get(0).publishedYear()).isEqualTo(2025);
        assertThat(result.books().get(0).coverUrl()).isEqualTo("http://example.com/b.jpg");
        assertThat(result.books().get(0).chatSessionCount()).isEqualTo(3L);
        assertThat(result.books().get(1).userBookId()).isEqualTo(10L);
        assertThat(result.books().get(1).bookId()).isEqualTo(1L);
        assertThat(result.books().get(1).title()).isEqualTo("이전에 등록한 책");
        assertThat(result.books().get(1).chatSessionCount()).isEqualTo(0L);
    }

    @Test
    void 등록된_도서가_없으면_빈_books_리스트를_반환한다() {
        given(userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(USER_ID))
                .willReturn(List.of());

        UserBookSearchResult result = userBookSearchService.findMyBooks(USER_ID);

        assertThat(result.books()).isEmpty();
    }
}
