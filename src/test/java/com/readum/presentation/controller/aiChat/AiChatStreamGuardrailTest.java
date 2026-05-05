package com.readum.presentation.controller.aiChat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiStreamChatService;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.controller.aiChat.dto.AiChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("guardrail")
@ExtendWith(MockitoExtension.class)
class AiChatStreamGuardrailTest {

    @Mock
    private AiStreamChatService aiStreamChatService;

    @Mock
    private AiChatSessionCreateService aiChatSessionCreateService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AiChatController controller = new AiChatController(
                aiStreamChatService, aiChatSessionCreateService, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void G10_4000자_초과_입력은_400_으로_거부된다() throws Exception {
        String tooLong = "안".repeat(4001);
        AiChatRequest body = new AiChatRequest(tooLong);

        mockMvc.perform(post("/api/v1/ai-chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("4000자")));
    }

    @Test
    void 빈_메시지는_400_으로_거부된다() throws Exception {
        AiChatRequest body = new AiChatRequest("   ");

        mockMvc.perform(post("/api/v1/ai-chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("비어")));
    }

    @Test
    void 정상_요청은_text_event_stream_미디어타입의_async_응답을_시작한다() throws Exception {
        given(aiStreamChatService.stream(any())).willReturn(Flux.just("이 책은", " 흥미롭습니다."));
        AiChatRequest body = new AiChatRequest("이 책의 줄거리를 요약해줘");

        mockMvc.perform(post("/api/v1/ai-chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }
}
