package com.readum.domain.userbook.service;

import com.readum.domain.exception.ConflictException;
import com.readum.domain.userbook.dto.UserBookCreateCommand;
import com.readum.domain.userbook.dto.UserBookCreateResult;
import com.readum.domain.userbook.exception.UserBookErrorCode;
import com.readum.model.book.entity.Book;
import com.readum.model.book.entity.UserBook;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.book.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserBookCreateServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @InjectMocks
    private UserBookCreateService userBookCreateService;

    private static final Long USER_ID = 1L;
    private static final Long BOOK_ID = 10L;
    private static final String EXTERNAL_ID = "9788965700807";
    private static final String TITLE = "테스트 책";
    private static final String AUTHORS = "테스트 저자";
    private static final String PUBLISHER = "테스트 출판사";
    private static final Integer PUBLISHED_YEAR = 2024;
    private static final String COVER_URL = "http://example.com/cover.jpg";

    private UserBookCreateCommand command() {
        return new UserBookCreateCommand(USER_ID, EXTERNAL_ID, TITLE, AUTHORS, PUBLISHER, PUBLISHED_YEAR, COVER_URL);
    }

    private Book stubBook() {
        return Book.of(BOOK_ID, EXTERNAL_ID, TITLE, AUTHORS, PUBLISHER, PUBLISHED_YEAR, COVER_URL, LocalDateTime.now());
    }

    @Test
    void 신규_도서_등록_시_upsert_호출_후_UserBook이_저장되고_올바른_결과를_반환한다() {
        Book book = stubBook();
        LocalDateTime savedAt = LocalDateTime.of(2024, 6, 1, 12, 0);
        UserBook savedUserBook = UserBook.of(100L, USER_ID, BOOK_ID, savedAt);

        given(bookRepository.findByExternalId(EXTERNAL_ID)).willReturn(Optional.of(book));
        given(userBookRepository.findByUserIdAndBookId(USER_ID, BOOK_ID)).willReturn(Optional.empty());
        given(userBookRepository.save(any(UserBook.class))).willReturn(savedUserBook);

        UserBookCreateResult result = userBookCreateService.execute(command());

        // upsert 호출 검증
        verify(bookRepository).upsert(EXTERNAL_ID, TITLE, AUTHORS, PUBLISHER, PUBLISHED_YEAR, COVER_URL);

        // 저장된 UserBook의 필드 검증
        ArgumentCaptor<UserBook> userBookCaptor = ArgumentCaptor.forClass(UserBook.class);
        verify(userBookRepository).save(userBookCaptor.capture());
        assertThat(userBookCaptor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(userBookCaptor.getValue().getBookId()).isEqualTo(BOOK_ID);

        // 반환 결과 검증
        assertThat(result.id()).isEqualTo(100L);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.bookExternalId()).isEqualTo(EXTERNAL_ID);
        assertThat(result.title()).isEqualTo(TITLE);
        assertThat(result.authors()).isEqualTo(AUTHORS);
        assertThat(result.createdAt()).isEqualTo(savedAt);
    }

    @Test
    void 이미_등록된_도서_재등록_시_ALREADY_EXISTS_ErrorCode와_기존_UserBook_데이터가_payload에_담긴_ConflictException이_발생한다() {
        Book book = stubBook();
        LocalDateTime existingCreatedAt = LocalDateTime.of(2024, 1, 1, 0, 0);
        UserBook existingUserBook = UserBook.of(99L, USER_ID, BOOK_ID, existingCreatedAt);

        given(bookRepository.findByExternalId(EXTERNAL_ID)).willReturn(Optional.of(book));
        given(userBookRepository.findByUserIdAndBookId(USER_ID, BOOK_ID)).willReturn(Optional.of(existingUserBook));

        assertThatThrownBy(() -> userBookCreateService.execute(command()))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(UserBookErrorCode.ALREADY_EXISTS);
                    assertThat(ex.getPayload())
                            .asInstanceOf(InstanceOfAssertFactories.type(UserBookCreateResult.class))
                            .satisfies(payload -> {
                                assertThat(payload.id()).isEqualTo(99L);
                                assertThat(payload.userId()).isEqualTo(USER_ID);
                                assertThat(payload.bookExternalId()).isEqualTo(EXTERNAL_ID);
                                assertThat(payload.title()).isEqualTo(TITLE);
                                assertThat(payload.createdAt()).isEqualTo(existingCreatedAt);
                            });
                });
    }
}
