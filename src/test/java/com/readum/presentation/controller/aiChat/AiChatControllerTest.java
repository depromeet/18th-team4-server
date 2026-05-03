package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.dto.MessageResult;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.AiChatMessageSearchService;
import com.readum.domain.aiChat.service.AiChatMessageSendService;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiChatControllerTest {

    @Mock
    private AiChatSessionCreateService aiChatSessionCreateService;

    @Mock
    private AiChatMessageSendService aiChatMessageSendService;

    @Mock
    private AiChatMessageSearchService aiChatMessageSearchService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private static final Long AUTHENTICATED_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        AiChatController controller = new AiChatController(
                aiChatSessionCreateService,
                aiChatMessageSendService,
                aiChatMessageSearchService,
                new MessageStreamSseSerializer(objectMapper)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AUTHENTICATED_USER_ID, null, List.of())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 세션_생성_정상_요청시_201과_세션_id_를_반환한다() throws Exception {
        given(aiChatSessionCreateService.execute(any())).willReturn(new AiChatSessionCreateResult(42L));

        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(10L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(42));
    }

    @Test
    void 세션_생성_userBookId_누락시_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("userBookId는 필수")));
    }

    @Test
    void 세션_생성_userBookId_가_음수면_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(-1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("양수")));
    }

    @Test
    void 세션_생성_소유권_없는_userBookId_는_404() throws Exception {
        given(aiChatSessionCreateService.execute(any()))
                .willThrow(new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(999_999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("해당 도서를 찾을 수 없습니다."));
    }

    @Test
    void 메시지_전송_본문_누락시_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("메시지 본문은 비어 있을 수 없습니다.")));
    }

    @Test
    void 메시지_전송_빈_본문이면_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 메시지_전송_whitespace_본문이면_400_변환된다() throws Exception {
        // @NotBlank 가 trim 후 빈 문자열을 거절
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("   "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 메시지_전송_1001자_본문이면_400() throws Exception {
        String tooLong = "가".repeat(1001);
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest(tooLong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("1000자")));
    }

    @Test
    void 메시지_전송_세션_없으면_404() throws Exception {
        given(aiChatMessageSendService.execute(any()))
                .willThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("질문"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("세션을 찾을 수 없습니다."));
    }

    @Test
    void 메시지_전송_종료된_세션이면_400() throws Exception {
        given(aiChatMessageSendService.execute(any()))
                .willThrow(new BadRequestException(AiChatErrorCode.SESSION_CLOSED));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("질문"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("종료된 세션에는 메시지를 보낼 수 없습니다."));
    }

    @Test
    void 메시지_전송_정상_스트림이면_token_과_done_이벤트가_방출된다() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 2, 14, 33, 21);
        // ASCII payload — MockHttpServletResponse 의 기본 charset 이 SSE 에서 UTF-8 가 아니어서 한글은 mojibake 가능
        given(aiChatMessageSendService.execute(any())).willReturn(Flux.just(
                new MessageStreamEvent.Token("alpha"),
                new MessageStreamEvent.Token(" beta"),
                new MessageStreamEvent.Done(99L,
                        new MessageStreamEvent.TokenCount(312, 58, 370),
                        createdAt)
        ));

        MvcResult initial = mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("질문"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:token")
                .contains("data:{\"delta\":\"alpha\"}")
                .contains("data:{\"delta\":\" beta\"}")
                .contains("event:done")
                .contains("\"messageId\":99")
                .contains("\"total\":370");
    }

    @Test
    void 메시지_전송_스트림_에러_이벤트도_정상_방출된다() throws Exception {
        given(aiChatMessageSendService.execute(any())).willReturn(Flux.just(
                new MessageStreamEvent.Token("부분"),
                MessageStreamEvent.Error.of(
                        AiChatErrorCode.AI_STREAM_INTERRUPTED.name(),
                        AiChatErrorCode.AI_STREAM_INTERRUPTED.getMessage()
                )
        ));

        MvcResult initial = mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("질문"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:token")
                .contains("event:error")
                .contains("\"code\":\"AI_STREAM_INTERRUPTED\"");
    }

    @Test
    void 메시지_조회_정상_응답() throws Exception {
        given(aiChatMessageSearchService.findBySessionId(any()))
                .willReturn(new MessageListResult(
                        List.of(
                                new MessageResult(
                                        2L,
                                        AiChatMessage.Role.ASSISTANT,
                                        "이 책의 주제는 모험입니다.",
                                        370,
                                        AiChatMessage.Status.COMPLETED,
                                        LocalDateTime.of(2026, 5, 2, 14, 33, 22)
                                ),
                                new MessageResult(
                                        1L,
                                        AiChatMessage.Role.USER,
                                        "주제가 뭐야",
                                        null,
                                        AiChatMessage.Status.COMPLETED,
                                        LocalDateTime.of(2026, 5, 2, 14, 33, 21)
                                )
                        ),
                        1,
                        20,
                        false
                ));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/7/messages")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[0].tokenCount").value(370))
                .andExpect(jsonPath("$.data.messages[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.messages[1].role").value("USER"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 메시지_조회_page_가_0이면_400() throws Exception {
        mockMvc.perform(get("/api/v1/ai-chat/sessions/7/messages")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("page는 1 이상")));
    }

    @Test
    void 메시지_조회_size_가_101이면_400() throws Exception {
        mockMvc.perform(get("/api/v1/ai-chat/sessions/7/messages")
                        .param("page", "1")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("size는 100 이하")));
    }
}
