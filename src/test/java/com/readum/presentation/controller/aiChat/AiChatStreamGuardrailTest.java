package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.service.AiChatMessageGetService;
import com.readum.domain.aiChat.service.AiChatMessageSendService;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.SummaryDraftService;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import jakarta.servlet.http.Cookie;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 스트림 엔드포인트의 입력 가드레일(길이/공백 검증) 과 정상 async 시작을 검증.
 * 본문 길이/빈 본문 검증은 일반 {@link AiChatControllerTest} 에도 있지만,
 * 가드레일 회귀 슈트(`./gradlew guardrailTest`) 로 함께 실행되도록 @Tag("guardrail") 부여.
 */
@Tag("guardrail")
@ExtendWith(MockitoExtension.class)
class AiChatStreamGuardrailTest {

    private static final Cookie USER_SESSION_COOKIE = new Cookie("user_session", "test-session-id");

    @Mock
    private AiChatSessionCreateService aiChatSessionCreateService;

    @Mock
    private AiChatMessageSendService aiChatMessageSendService;

    @Mock
    private AiChatMessageGetService aiChatMessageGetService;

    @Mock
    private SummaryDraftService summaryDraftService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @BeforeEach
    void setUp() {
        AiChatController controller = new AiChatController(
                aiChatSessionCreateService,
                null,
                aiChatMessageSendService,
                aiChatMessageGetService,
                summaryDraftService,
                null,
                null,
                new MessageStreamSseSerializer(objectMapper)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void G10_4000자_초과_입력은_400_으로_거부된다() throws Exception {
        String tooLong = "안".repeat(4001);
        SendMessageRequest body = new SendMessageRequest(tooLong);

        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .cookie(USER_SESSION_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("4000자")));
    }

    @Test
    void 빈_메시지는_400_으로_거부된다() throws Exception {
        SendMessageRequest body = new SendMessageRequest("   ");

        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .cookie(USER_SESSION_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("비어")));
    }

    @Test
    void 정상_요청은_text_event_stream_미디어타입의_async_응답을_시작한다() throws Exception {
        given(aiChatMessageSendService.execute(any())).willReturn(Flux.just(
                new MessageStreamEvent.Token("이 책은"),
                new MessageStreamEvent.Token(" 흥미롭습니다."),
                new MessageStreamEvent.Done(
                        99L,
                        new MessageStreamEvent.TokenCount(10, 5, 15),
                        LocalDateTime.of(2026, 5, 2, 14, 33, 21)
                )
        ));
        SendMessageRequest body = new SendMessageRequest("이 책의 줄거리를 요약해줘");

        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .cookie(USER_SESSION_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }
}
