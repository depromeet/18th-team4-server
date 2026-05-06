package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionListCommand;
import com.readum.domain.aiChat.dto.AiChatSessionListResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
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
class AiChatSessionGetServiceTest {

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @InjectMocks
    private AiChatSessionGetService aiChatSessionGetService;

    @Test
    void 소유권_없는_userBookId_면_NotFoundException() {
        Long userId = 1L;
        Long userBookId = 100L;
        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatSessionGetService.findByUserBookId(
                new AiChatSessionListCommand(userId, userBookId, 1, 20)
        ))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_BOOK_NOT_FOUND);
    }

    @Test
    void 세션이_없으면_빈_배열과_hasNext_false_를_반환한다() {
        Long userId = 1L;
        Long userBookId = 100L;
        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                userBookId, userId, PageRequest.of(0, 20)
        )).willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        AiChatSessionListResult result = aiChatSessionGetService.findByUserBookId(
                new AiChatSessionListCommand(userId, userBookId, 1, 20)
        );

        assertThat(result.sessions()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void ACTIVE_SUMMARIZING_CLOSED_가_섞여있어도_상태_그대로_변환된다() {
        Long userId = 1L;
        Long userBookId = 100L;
        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.of(mock(UserBook.class)));

        LocalDateTime base = LocalDateTime.of(2026, 5, 7, 12, 0, 0);
        List<AiChatSessionListProjection> rows = List.of(
                new AiChatSessionListProjection(3L, "최근 세션", AiChatSession.Status.SUMMARIZING, base.plusMinutes(2)),
                new AiChatSessionListProjection(2L, "두 번째", AiChatSession.Status.ACTIVE, base.plusMinutes(1)),
                new AiChatSessionListProjection(1L, "오래된", AiChatSession.Status.CLOSED, base)
        );
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                userBookId, userId, PageRequest.of(0, 20)
        )).willReturn(new SliceImpl<>(rows, PageRequest.of(0, 20), false));

        AiChatSessionListResult result = aiChatSessionGetService.findByUserBookId(
                new AiChatSessionListCommand(userId, userBookId, 1, 20)
        );

        assertThat(result.sessions()).hasSize(3);
        assertThat(result.sessions().get(0).status()).isEqualTo(AiChatSession.Status.SUMMARIZING);
        assertThat(result.sessions().get(1).status()).isEqualTo(AiChatSession.Status.ACTIVE);
        assertThat(result.sessions().get(2).status()).isEqualTo(AiChatSession.Status.CLOSED);
        assertThat(result.sessions().get(0).title()).isEqualTo("최근 세션");
        assertThat(result.sessions().get(0).lastChattedAt()).isEqualTo(base.plusMinutes(2));
    }

    @Test
    void 다음_페이지가_있으면_hasNext_true_를_반환한다() {
        Long userId = 1L;
        Long userBookId = 100L;
        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.of(mock(UserBook.class)));

        List<AiChatSessionListProjection> rows = List.of(
                new AiChatSessionListProjection(2L, "t2", AiChatSession.Status.ACTIVE, LocalDateTime.now()),
                new AiChatSessionListProjection(1L, "t1", AiChatSession.Status.ACTIVE, LocalDateTime.now().minusMinutes(1))
        );
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                userBookId, userId, PageRequest.of(0, 2)
        )).willReturn(new SliceImpl<>(rows, PageRequest.of(0, 2), true));

        AiChatSessionListResult result = aiChatSessionGetService.findByUserBookId(
                new AiChatSessionListCommand(userId, userBookId, 1, 2)
        );

        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void page_파라미터는_1_indexed_에서_0_indexed_로_변환된다() {
        Long userId = 1L;
        Long userBookId = 100L;
        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                userBookId, userId, PageRequest.of(2, 10)
        )).willReturn(new SliceImpl<>(List.of(), PageRequest.of(2, 10), false));

        AiChatSessionListResult result = aiChatSessionGetService.findByUserBookId(
                new AiChatSessionListCommand(userId, userBookId, 3, 10)
        );

        assertThat(result.page()).isEqualTo(3);
        assertThat(result.size()).isEqualTo(10);
    }
}
