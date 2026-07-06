package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionDisplayStatus;
import com.readum.domain.aiChat.dto.AiChatSessionListCommand;
import com.readum.domain.aiChat.dto.AiChatSessionListResult;
import com.readum.domain.aiChat.dto.AiChatSessionResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
import com.readum.model.userBook.entity.UserBook;
import com.readum.model.userBook.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AiChatSessionSearchServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long USER_BOOK_ID = 100L;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @InjectMocks
    private AiChatSessionSearchService aiChatSessionSearchService;

    @Test
    void 소유권_없는_userBookId_면_NotFoundException() {
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatSessionSearchService.findByUserBookId(
                new AiChatSessionListCommand(USER_ID, USER_BOOK_ID, 1, 20)
        ))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_BOOK_NOT_FOUND);
    }

    @Test
    void 세션이_없으면_빈_배열과_hasNext_false_를_반환한다() {
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                USER_BOOK_ID, USER_ID, PageRequest.of(0, 20)
        )).willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        AiChatSessionListResult result = aiChatSessionSearchService.findByUserBookId(
                new AiChatSessionListCommand(USER_ID, USER_BOOK_ID, 1, 20)
        );

        assertThat(result.sessions()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void ACTIVE_SUMMARIZING_SUMMARIZED_상태가_그대로_변환된다() {
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        LocalDateTime base = LocalDateTime.of(2026, 5, 7, 12, 0, 0);
        // Repository 가 status 를 이미 String 으로, lastChattedAt 을 LocalDateTime 으로 내려보내고
        // service 는 변환 없이 Result 로 전달. Result 단계에서 LocalDate 로 narrow.
        List<AiChatSessionListProjection> rows = List.of(
                new AiChatSessionListProjection(4L, "최근", "SUMMARIZING", base.plusMinutes(3)),
                new AiChatSessionListProjection(3L, "활성", "ACTIVE", base.plusMinutes(2)),
                new AiChatSessionListProjection(2L, "감상문 완료", "SUMMARIZED", base.minusDays(1))
        );
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                USER_BOOK_ID, USER_ID, PageRequest.of(0, 20)
        )).willReturn(new SliceImpl<>(rows, PageRequest.of(0, 20), false));

        AiChatSessionListResult result = aiChatSessionSearchService.findByUserBookId(
                new AiChatSessionListCommand(USER_ID, USER_BOOK_ID, 1, 20)
        );

        assertThat(result.sessions()).hasSize(3);
        assertThat(result.sessions()).extracting(AiChatSessionResult::status)
                .containsExactly(
                        AiChatSessionDisplayStatus.SUMMARIZING,
                        AiChatSessionDisplayStatus.ACTIVE,
                        AiChatSessionDisplayStatus.SUMMARIZED
                );
        assertThat(result.sessions().get(0).lastChattedDate()).isEqualTo(java.time.LocalDate.of(2026, 5, 7));
        assertThat(result.sessions().get(2).lastChattedDate()).isEqualTo(java.time.LocalDate.of(2026, 5, 6));
    }

    @Test
    void 다음_페이지가_있으면_hasNext_true_를_반환한다() {
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        List<AiChatSessionListProjection> rows = List.of(
                new AiChatSessionListProjection(2L, "t2", "ACTIVE", LocalDateTime.now()),
                new AiChatSessionListProjection(1L, "t1", "ACTIVE", LocalDateTime.now().minusMinutes(1))
        );
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                USER_BOOK_ID, USER_ID, PageRequest.of(0, 2)
        )).willReturn(new SliceImpl<>(rows, PageRequest.of(0, 2), true));

        AiChatSessionListResult result = aiChatSessionSearchService.findByUserBookId(
                new AiChatSessionListCommand(USER_ID, USER_BOOK_ID, 1, 2)
        );

        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void page_파라미터는_1_indexed_에서_0_indexed_로_변환된다() {
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                USER_BOOK_ID, USER_ID, PageRequest.of(2, 10)
        )).willReturn(new SliceImpl<>(List.of(), PageRequest.of(2, 10), false));

        AiChatSessionListResult result = aiChatSessionSearchService.findByUserBookId(
                new AiChatSessionListCommand(USER_ID, USER_BOOK_ID, 3, 10)
        );

        assertThat(result.page()).isEqualTo(3);
        assertThat(result.size()).isEqualTo(10);
    }
}
