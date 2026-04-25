package com.readum.presentation.controller.aiChat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiStreamChatService;
import com.readum.domain.exception.NotFoundException;
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

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long AUTHENTICATED_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        AiChatController controller = new AiChatController(aiStreamChatService, aiChatSessionCreateService);
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
}
