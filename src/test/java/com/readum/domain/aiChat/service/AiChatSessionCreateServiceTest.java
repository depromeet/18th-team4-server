package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionCreateCommand;
import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.entity.UserBookFixture;
import com.readum.model.user.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatSessionCreateServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @InjectMocks
    private AiChatSessionCreateService aiChatSessionCreateService;

    @Test
    void 본인_소유_userBook_으로_세션_생성_성공() {
        Long userBookId = 10L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(USER_ID, userBookId);

        UserBook userBook = UserBookFixture.persistedUserBook(userBookId, USER_ID, 100L);
        given(userBookRepository.findByIdAndUserId(userBookId, USER_ID)).willReturn(Optional.of(userBook));

        AiChatSession persisted = AiChatSessionFixture.persistedActiveSession(
                42L, userBookId, 0, 0, null
        );
        given(aiChatSessionRepository.save(any(AiChatSession.class))).willReturn(persisted);

        AiChatSessionCreateResult result = aiChatSessionCreateService.execute(command);

        assertThat(result.id()).isEqualTo(42L);
    }

    @Test
    void 다른_사용자_소유_userBook_은_NotFoundException_을_던지고_save_는_호출되지_않는다() {
        Long userBookId = 10L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(USER_ID, userBookId);

        given(userBookRepository.findByIdAndUserId(userBookId, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatSessionCreateService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_BOOK_NOT_FOUND);

        verify(aiChatSessionRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_userBook_도_동일한_USER_BOOK_NOT_FOUND_예외() {
        Long userBookId = 999_999L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(USER_ID, userBookId);

        given(userBookRepository.findByIdAndUserId(userBookId, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatSessionCreateService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_BOOK_NOT_FOUND);
    }
}
