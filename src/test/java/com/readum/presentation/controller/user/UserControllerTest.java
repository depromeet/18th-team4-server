package com.readum.presentation.controller.user;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.UserProfileResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.domain.user.service.CompleteOnboardingService;
import com.readum.domain.user.service.CreateUserSessionService;
import com.readum.domain.user.service.UserSearchService;
import com.readum.presentation.common.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final Cookie USER_SESSION_COOKIE = new Cookie("user_session", "test-session-id");

    @Mock
    private CreateUserSessionService createUserSessionService;

    @Mock
    private UserSearchService userSearchService;

    @Mock
    private CompleteOnboardingService completeOnboardingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController(
                createUserSessionService, userSearchService, completeOnboardingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 유효한_세션으로_프로필을_조회하면_닉네임을_반환한다() throws Exception {
        given(userSearchService.findProfile("test-session-id"))
                .willReturn(new UserProfileResult("문장수집가"));

        mockMvc.perform(get("/api/v1/users/me/profile").cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.nickname").value("문장수집가"));
    }

    @Test
    void 닉네임이_없는_기존_사용자도_프로필_조회시_닉네임_키가_null_로_노출된다() throws Exception {
        given(userSearchService.findProfile("test-session-id"))
                .willReturn(new UserProfileResult(null));

        mockMvc.perform(get("/api/v1/users/me/profile").cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.nickname").value(nullValue()));
    }

    @Test
    void 세션_쿠키가_없으면_401_을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 유효하지_않은_세션이면_401_을_반환한다() throws Exception {
        given(userSearchService.findProfile("test-session-id"))
                .willThrow(new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        mockMvc.perform(get("/api/v1/users/me/profile").cookie(USER_SESSION_COOKIE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value(UserErrorCode.INVALID_SESSION.getMessage()));
    }
}
