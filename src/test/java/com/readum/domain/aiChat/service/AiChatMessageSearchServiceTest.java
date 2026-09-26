package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.MessageListCommand;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiChatMessageSearchServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @InjectMocks
    private AiChatMessageSearchService aiChatMessageSearchService;

    @Test
    void 소유권_없는_세션이면_NotFoundException() {
        Long sessionId = 7L;
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatMessageSearchService.findBySessionId(
                new MessageListCommand(USER_ID, sessionId, 1, 20)
        ))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 소유_세션의_페이지_결과를_변환한다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 0, 0, null
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, USER_ID)).willReturn(Optional.of(session));

        List<AiChatMessage> rows = List.of(
                AiChatMessageFixture.persistedAssistantMessage(
                        2L, sessionId, "응답 본문", "인용", 10, 5, 15
                ),
                AiChatMessageFixture.persistedUserMessage(
                        1L, sessionId, "사용자 메시지"
                )
        );
        given(aiChatMessageRepository.findVisibleHistory(
                sessionId, PageRequest.of(0, 20)
        )).willReturn(new SliceImpl<>(rows, PageRequest.of(0, 20), false));

        MessageListResult result = aiChatMessageSearchService.findBySessionId(
                new MessageListCommand(USER_ID, sessionId, 1, 20)
        );

        assertThat(result.messages()).hasSize(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.messages().get(0).role()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(result.messages().get(0).totalTokens()).isEqualTo(15);
    }
}
