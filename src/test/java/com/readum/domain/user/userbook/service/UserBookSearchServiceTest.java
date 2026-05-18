package com.readum.domain.user.userbook.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.domain.user.userbook.dto.UserBookSearchResult;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import com.readum.model.user.repository.projection.UserBookListItemProjection;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserBookSearchServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @InjectMocks
    private UserBookSearchService userBookSearchService;

    private static final Long USER_ID = 1L;
    private static final String USER_SESSION_ID = "test-session-id";

    private User stubUser() {
        return UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
    }

    @Test
    void 유효한_user_session_으로_조회하면_등록한_도서_목록을_반환한다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(USER_ID))
                .willReturn(List.of(
                        new UserBookListItemProjection(20L, 2L, "최근 등록한 책", "출판사B", 2025, "http://example.com/b.jpg"),
                        new UserBookListItemProjection(10L, 1L, "이전에 등록한 책", "출판사A", 2024, "http://example.com/a.jpg")
                ));

        UserBookSearchResult result = userBookSearchService.findMyBooks(USER_SESSION_ID);

        assertThat(result.books()).hasSize(2);
        assertThat(result.books().get(0).userBookId()).isEqualTo(20L);
        assertThat(result.books().get(0).bookId()).isEqualTo(2L);
        assertThat(result.books().get(0).title()).isEqualTo("최근 등록한 책");
        assertThat(result.books().get(0).publisher()).isEqualTo("출판사B");
        assertThat(result.books().get(0).publishedYear()).isEqualTo(2025);
        assertThat(result.books().get(0).coverUrl()).isEqualTo("http://example.com/b.jpg");
        assertThat(result.books().get(1).userBookId()).isEqualTo(10L);
        assertThat(result.books().get(1).bookId()).isEqualTo(1L);
        assertThat(result.books().get(1).title()).isEqualTo("이전에 등록한 책");
    }

    @Test
    void 등록된_도서가_없으면_빈_books_리스트를_반환한다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(USER_ID))
                .willReturn(List.of());

        UserBookSearchResult result = userBookSearchService.findMyBooks(USER_SESSION_ID);

        assertThat(result.books()).isEmpty();
    }

    @Test
    void 세션이_유효하지_않으면_UnauthorizedException_을_던진다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userBookSearchService.findMyBooks(USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.INVALID_SESSION));
    }
}
