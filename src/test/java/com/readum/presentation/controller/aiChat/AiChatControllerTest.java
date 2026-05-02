package com.readum.presentation.controller.aiChat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiStreamChatService;
import com.readum.domain.aiChat.service.SummaryDraftService;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiChatControllerTest {

    @Mock
    private AiStreamChatService aiStreamChatService;

    @Mock
    private AiChatSessionCreateService aiChatSessionCreateService;

    @Mock
    private SummaryDraftService summaryDraftService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long AUTHENTICATED_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        AiChatController controller = new AiChatController(aiStreamChatService, aiChatSessionCreateService, summaryDraftService);
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
    void 정상_요청시_201과_세션_id_를_반환한다() throws Exception {
        given(aiChatSessionCreateService.execute(any())).willReturn(new AiChatSessionCreateResult(42L));

        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(10L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(42));
    }

    @Test
    void userBookId_누락시_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("userBookId는 필수")));
    }

    @Test
    void userBookId_가_음수면_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(-1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("양수")));
    }

    @Test
    void 소유권_없는_userBookId_는_404() throws Exception {
        given(aiChatSessionCreateService.execute(any()))
                .willThrow(new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(999_999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("해당 도서를 찾을 수 없습니다."));
    }

    // ── 감상문 초안 생성 ──────────────────────────────────────────────────────

    @Test
    void 정상_요청시_200과_감상문_초안을_반환한다() throws Exception {
        SummaryDraftResult result = new SummaryDraftResult("나의 독서 감상", "깊은 울림을 주는 책이었다.", "선택의 기로에서");
        given(summaryDraftService.execute(eq(1L), eq(AUTHENTICATED_USER_ID))).willReturn(result);

        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("나의 독서 감상"))
                .andExpect(jsonPath("$.data.body").value("깊은 울림을 주는 책이었다."))
                .andExpect(jsonPath("$.data.quote").value("선택의 기로에서"));
    }

    @Test
    void 누적_토큰이_부족하면_422를_반환한다() throws Exception {
        given(summaryDraftService.execute(eq(1L), eq(AUTHENTICATED_USER_ID)))
                .willThrow(new UnprocessableEntityException(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("감상문 초안을 생성하기에 대화량이 부족합니다."));
    }

    @Test
    void 이미_닫힌_세션이면_409를_반환한다() throws Exception {
        given(summaryDraftService.execute(eq(1L), eq(AUTHENTICATED_USER_ID)))
                .willThrow(new ConflictException(AiChatErrorCode.SESSION_ALREADY_CLOSED));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("이미 감상문이 작성된 세션입니다."));
    }

    @Test
    void 존재하지_않는_세션이면_404를_반환한다() throws Exception {
        given(summaryDraftService.execute(eq(1L), eq(AUTHENTICATED_USER_ID)))
                .willThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("채팅 세션을 찾을 수 없습니다."));
    }
}
