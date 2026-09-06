package com.readum.presentation.controller.user;

import com.readum.domain.user.dto.UserProfileResult;
import com.readum.domain.user.service.CompleteOnboardingService;
import com.readum.domain.user.service.CreateUserSessionService;
import com.readum.domain.user.service.UpdateNicknameService;
import com.readum.domain.user.service.UserSearchService;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.common.security.AuthenticatedUserIdArgumentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private CreateUserSessionService createUserSessionService;

    @Mock
    private UserSearchService userSearchService;

    @Mock
    private CompleteOnboardingService completeOnboardingService;

    @Mock
    private UpdateNicknameService updateNicknameService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController(
                createUserSessionService, userSearchService, completeOnboardingService, updateNicknameService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedUserIdArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자가_프로필을_조회하면_닉네임을_반환한다() throws Exception {
        given(userSearchService.findProfile(USER_ID))
                .willReturn(new UserProfileResult("문장수집가"));

        mockMvc.perform(get("/api/v1/users/me/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.nickname").value("문장수집가"));
    }

    @Test
    void 닉네임이_없는_기존_사용자도_프로필_조회시_닉네임_키가_null_로_노출된다() throws Exception {
        given(userSearchService.findProfile(USER_ID))
                .willReturn(new UserProfileResult(null));

        mockMvc.perform(get("/api/v1/users/me/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.nickname").value(nullValue()));
    }
}
