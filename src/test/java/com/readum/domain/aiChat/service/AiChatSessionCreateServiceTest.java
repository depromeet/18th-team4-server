package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionCreateCommand;
import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatSessionCreateServiceTest {

    private static final Long USER_ID = 1L;
    private static final String USER_SESSION_ID = "test-session-id";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @InjectMocks
    private AiChatSessionCreateService aiChatSessionCreateService;

    @BeforeEach
    void setUp() {
        User testUser = User.of(USER_ID, null, USER_SESSION_ID, null, false,
                LocalDateTime.now(), LocalDateTime.now());
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
    }

    @Test
    void 본인_소유_userBook_으로_세션_생성_성공() {
        Long userBookId = 10L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(USER_SESSION_ID, userBookId);

        UserBook userBook = UserBook.of(userBookId, USER_ID, 100L, LocalDateTime.now());
        given(userBookRepository.findByIdAndUserId(userBookId, USER_ID)).willReturn(Optional.of(userBook));

        LocalDateTime now = LocalDateTime.now();
        AiChatSession persisted = AiChatSession.of(
                42L, userBookId, AiChatSession.Status.ACTIVE, 0, 0, null, now, now
        );
        given(aiChatSessionRepository.save(any(AiChatSession.class))).willReturn(persisted);

        AiChatSessionCreateResult result = aiChatSessionCreateService.execute(command);

        assertThat(result.id()).isEqualTo(42L);
    }

    @Test
    void 다른_사용자_소유_userBook_은_NotFoundException_을_던지고_save_는_호출되지_않는다() {
        Long userBookId = 10L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(USER_SESSION_ID, userBookId);

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
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(USER_SESSION_ID, userBookId);

        given(userBookRepository.findByIdAndUserId(userBookId, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatSessionCreateService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_BOOK_NOT_FOUND);
    }

    @Test
    void 유효하지_않은_session_쿠키면_UnauthorizedException() {
        given(userRepository.findBySessionId("invalid")).willReturn(Optional.empty());
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand("invalid", 10L);

        assertThatThrownBy(() -> aiChatSessionCreateService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }
}
