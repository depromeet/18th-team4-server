package com.readum.presentation.controller.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.user.userbook.dto.UserBookCreateResult;
import com.readum.domain.user.userbook.exception.UserBookErrorCode;
import com.readum.domain.user.userbook.service.UserBookCreateService;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.controller.user.dto.UserBookCreateRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserBookControllerTest {

    private static final Cookie USER_SESSION_COOKIE = new Cookie("user_session", "test-session-id");

    @Mock
    private UserBookCreateService userBookCreateService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        UserBookController controller = new UserBookController(userBookCreateService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    private String validRequestBody() throws Exception {
        return objectMapper.writeValueAsString(new UserBookCreateRequest("9788965700807"));
    }

    @Test
    void 유효한_요청_시_201과_Location_헤더_및_등록된_UserBook_정보를_반환한다() throws Exception {
        UserBookCreateResult result = new UserBookCreateResult(
                100L, 1L, "9788965700807", "테스트 책", "테스트 저자",
                "테스트 출판사", 2024, "http://example.com/cover.jpg",
                LocalDateTime.of(2024, 6, 1, 12, 0)
        );
        given(userBookCreateService.execute(any())).willReturn(result);

        mockMvc.perform(post("/api/v1/user-books")
                        .cookie(USER_SESSION_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/api/v1/user-books/100"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.bookExternalId").value("9788965700807"))
                .andExpect(jsonPath("$.data.title").value("테스트 책"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void bookExternalId가_없으면_400을_반환한다() throws Exception {
        String bodyWithoutExternalId = objectMapper.writeValueAsString(new UserBookCreateRequest(null));

        mockMvc.perform(post("/api/v1/user-books")
                        .cookie(USER_SESSION_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutExternalId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void user_session_쿠키가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/user-books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void 이미_등록된_도서면_409와_error_payload를_GlobalExceptionHandler가_직렬화하여_반환한다() throws Exception {
        UserBookCreateResult existingResult = new UserBookCreateResult(
                100L, 1L, "9788965700807", "테스트 책", "테스트 저자",
                "테스트 출판사", 2024, "http://example.com/cover.jpg",
                LocalDateTime.of(2024, 6, 1, 12, 0)
        );
        given(userBookCreateService.execute(any()))
                .willThrow(new ConflictException(UserBookErrorCode.ALREADY_EXISTS, existingResult));

        mockMvc.perform(post("/api/v1/user-books")
                        .cookie(USER_SESSION_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("이미 책장에 등록된 도서입니다."))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.bookExternalId").value("9788965700807"));
    }
}
