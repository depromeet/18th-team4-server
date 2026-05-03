package com.readum.domain.aiChat.history;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ChatHistoryBuilderTest {

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    private final AiChatProperties properties = new AiChatProperties(
            new AiChatProperties.ContextWindow(20),
            new AiChatProperties.MessageRule(1000),
            new AiChatProperties.RateLimit(10, 5)
    );

    private ChatHistoryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ChatHistoryBuilder(aiChatMessageRepository, properties);
    }

    @Test
    void 최근_40개_이전_메시지를_시간순으로_정렬해_반환한다() {
        Long sessionId = 7L;
        List<AiChatMessage> recentDesc = new ArrayList<>();
        for (int i = 40; i >= 1; i--) {
            AiChatMessage.Role role = (i % 2 == 0) ? AiChatMessage.Role.ASSISTANT : AiChatMessage.Role.USER;
            recentDesc.add(AiChatMessage.of(
                    (long) i,
                    sessionId,
                    role,
                    "내용 " + i,
                    null,
                    null,
                    null,
                    null,
                    AiChatMessage.Status.COMPLETED,
                    LocalDateTime.now().minusMinutes(40 - i)
            ));
        }
        given(aiChatMessageRepository.findRecentForContextWindow(sessionId, PageRequest.of(0, 40)))
                .willReturn(recentDesc);

        List<HistoryMessage> history = builder.buildPreviousHistory(sessionId);

        assertThat(history).hasSize(40);
        assertThat(history.get(0).content()).isEqualTo("내용 1");
        assertThat(history.get(0).role()).isEqualTo(HistoryMessage.Role.USER);
        assertThat(history.get(39).content()).isEqualTo("내용 40");
        assertThat(history.get(39).role()).isEqualTo(HistoryMessage.Role.ASSISTANT);
    }

    @Test
    void 메시지가_적으면_있는_만큼만_history_에_포함된다() {
        Long sessionId = 7L;
        AiChatMessage onlyOne = AiChatMessage.of(
                1L,
                sessionId,
                AiChatMessage.Role.USER,
                "이전 user 메시지",
                null, null, null, null,
                AiChatMessage.Status.COMPLETED,
                LocalDateTime.now()
        );
        given(aiChatMessageRepository.findRecentForContextWindow(sessionId, PageRequest.of(0, 40)))
                .willReturn(List.of(onlyOne));

        List<HistoryMessage> history = builder.buildPreviousHistory(sessionId);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).content()).isEqualTo("이전 user 메시지");
    }

    @Test
    void 빈_세션이면_빈_history_가_반환된다() {
        Long sessionId = 7L;
        given(aiChatMessageRepository.findRecentForContextWindow(sessionId, PageRequest.of(0, 40)))
                .willReturn(List.of());

        List<HistoryMessage> history = builder.buildPreviousHistory(sessionId);

        assertThat(history).isEmpty();
    }
}
