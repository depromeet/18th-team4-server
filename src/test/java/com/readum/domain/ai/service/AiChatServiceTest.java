package com.readum.domain.ai.service;

import com.readum.domain.ai.dto.AiChatCommand;
import com.readum.domain.ai.dto.AiChatResult;
import com.readum.domain.ai.out.AiChatClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

    @Mock
    private AiChatClient aiChatClient;

    @InjectMocks
    private AiChatService aiChatService;

    @Test
    void 정상_메시지를_전달하면_AI_응답을_반환한다() {
        AiChatCommand command = new AiChatCommand("오늘 읽은 책에 대해 알려줘");
        AiChatResult expected = new AiChatResult("오늘 읽은 책은 정말 흥미롭군요!");
        given(aiChatClient.chat(command)).willReturn(expected);

        AiChatResult result = aiChatService.execute(command);

        assertThat(result.answer()).isEqualTo(expected.answer());
    }
}
