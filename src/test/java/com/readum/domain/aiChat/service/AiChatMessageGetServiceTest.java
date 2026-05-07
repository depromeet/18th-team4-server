package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.MessageListCommand;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AiChatMessageGetServiceTest {

    private static final Long USER_ID = 1L;
    private static final String USER_SESSION_ID = "test-session-id";

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @InjectMocks
    private AiChatMessageGetService aiChatMessageGetService;

    @BeforeEach
    void setUp() {
        User testUser = User.of(USER_ID, null, USER_SESSION_ID, null, false,
                LocalDateTime.now(), LocalDateTime.now());
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
    }

    @Test
    void 유효하지_않은_session_쿠키면_UnauthorizedException() {
        given(userRepository.findBySessionId("invalid")).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatMessageGetService.findBySessionId(
                new MessageListCommand("invalid", 7L, 1, 20)
        ))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }

    @Test
    void 소유권_없는_세션이면_NotFoundException() {
        Long sessionId = 7L;
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatMessageGetService.findBySessionId(
                new MessageListCommand(USER_SESSION_ID, sessionId, 1, 20)
        ))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 소유_세션의_페이지_결과를_변환한다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE, 0, 0, null,
                LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, USER_ID)).willReturn(Optional.of(session));

        List<AiChatMessage> rows = List.of(
                AiChatMessage.of(
                        2L, sessionId, AiChatMessage.Role.ASSISTANT,
                        "응답 본문", "인용", 10, 5, 15,
                        AiChatMessage.Status.COMPLETED, LocalDateTime.now()
                ),
                AiChatMessage.of(
                        1L, sessionId, AiChatMessage.Role.USER,
                        "사용자 메시지", null, null, null, null,
                        AiChatMessage.Status.COMPLETED, LocalDateTime.now().minusSeconds(1)
                )
        );
        given(aiChatMessageRepository.findVisibleHistory(
                sessionId, PageRequest.of(0, 20)
        )).willReturn(new SliceImpl<>(rows, PageRequest.of(0, 20), false));

        MessageListResult result = aiChatMessageGetService.findBySessionId(
                new MessageListCommand(USER_SESSION_ID, sessionId, 1, 20)
        );

        assertThat(result.messages()).hasSize(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.messages().get(0).role()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(result.messages().get(0).totalTokens()).isEqualTo(15);
    }
}
