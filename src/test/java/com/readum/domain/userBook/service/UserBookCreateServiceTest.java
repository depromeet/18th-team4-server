package com.readum.domain.userBook.service;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.exception.BookErrorCode;
import com.readum.domain.book.out.BookLookupClient;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.userBook.dto.UserBookCreateCommand;
import com.readum.domain.userBook.dto.UserBookCreateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserBookCreateServiceTest {

    @Mock
    private BookLookupClient bookLookupClient;

    @Mock
    private UserBookRegistrationWriter userBookRegistrationWriter;

    @InjectMocks
    private UserBookCreateService userBookCreateService;

    private static final Long USER_ID = 1L;
    private static final String EXTERNAL_ID = "9788965700807";
    private static final String TITLE = "테스트 책";
    private static final String AUTHORS = "테스트 저자";
    private static final String PUBLISHER = "테스트 출판사";
    private static final Integer PUBLISHED_YEAR = 2024;
    private static final String COVER_URL = "http://example.com/cover.jpg";

    private UserBookCreateCommand command() {
        return new UserBookCreateCommand(USER_ID, EXTERNAL_ID);
    }

    private BookResult stubBookResult() {
        return new BookResult(COVER_URL, TITLE, AUTHORS, PUBLISHER, PUBLISHED_YEAR, EXTERNAL_ID);
    }

    @Test
    void 도서_등록_시_알라딘_조회_후_그_결과로_DB_쓰기를_위임하고_쓰기_결과를_반환한다() {
        BookResult bookInfo = stubBookResult();
        UserBookCreateResult writerResult = new UserBookCreateResult(
                100L, USER_ID, EXTERNAL_ID, TITLE, AUTHORS, PUBLISHER, PUBLISHED_YEAR, COVER_URL,
                LocalDateTime.of(2024, 6, 1, 12, 0));

        given(bookLookupClient.execute(EXTERNAL_ID)).willReturn(bookInfo);
        given(userBookRegistrationWriter.register(command(), bookInfo)).willReturn(writerResult);

        UserBookCreateResult result = userBookCreateService.execute(command());

        assertThat(result).isEqualTo(writerResult);

        InOrder inOrder = Mockito.inOrder(bookLookupClient, userBookRegistrationWriter);
        inOrder.verify(bookLookupClient).execute(EXTERNAL_ID);
        inOrder.verify(userBookRegistrationWriter).register(command(), bookInfo);
    }

    @Test
    void 알라딘_조회가_실패하면_DB_쓰기를_시도하지_않고_예외가_그대로_전파된다() {
        NotFoundException lookupFailure = new NotFoundException(BookErrorCode.LOOKUP_NOT_FOUND);
        given(bookLookupClient.execute(EXTERNAL_ID)).willThrow(lookupFailure);

        assertThatThrownBy(() -> userBookCreateService.execute(command()))
                .isSameAs(lookupFailure);

        verifyNoInteractions(userBookRegistrationWriter);
    }
}
