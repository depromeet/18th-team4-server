package com.readum.presentation.controller.auth;

import com.readum.domain.auth.dto.AuthenticatedPrincipal;
import com.readum.domain.auth.dto.LogoutCommand;
import com.readum.domain.auth.dto.LogoutResult;
import com.readum.domain.auth.service.LogoutService;
import com.readum.domain.auth.service.TokenAuthenticationService;
import com.readum.domain.auth.service.TokenRefreshService;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.common.security.JwtAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerLogoutTest {

    private static final String LOGOUT_URI = "/api/v1/auth/logout";
    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    @Mock
    private LogoutService logoutService;

    @Mock
    private TokenRefreshService tokenRefreshService;

    @Mock
    private TokenAuthenticationService tokenAuthenticationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController(tokenRefreshService, logoutService, true);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JwtAuthenticationFilter(tokenAuthenticationService))
                .build();
    }

    @Test
    void RT_쿠키_AT_헤더_모두_없는_미인증_요청에도_RT_만료_쿠키가_반환된다() throws Exception {
        given(logoutService.execute(any())).willReturn(LogoutResult.anonymous());

        MvcResult result = mockMvc.perform(post(LOGOUT_URI))
                .andExpect(status().isNoContent())
                .andReturn();

        assertRefreshCookieCleared(result);
        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).execute(captor.capture());
        assertThat(captor.getValue().refreshToken()).isNull();
        assertThat(captor.getValue().accessToken()).isNull();
    }

    @Test
    void RT_쿠키만_있고_AT_헤더는_없는_요청에도_RT_만료_쿠키가_반환된다() throws Exception {
        given(logoutService.execute(any())).willReturn(LogoutResult.of(5L));

        MvcResult result = mockMvc.perform(post(LOGOUT_URI)
                        .cookie(new Cookie(REFRESH_TOKEN_COOKIE, "refresh-token-value")))
                .andExpect(status().isNoContent())
                .andReturn();

        assertRefreshCookieCleared(result);
        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).execute(captor.capture());
        assertThat(captor.getValue().refreshToken()).isEqualTo("refresh-token-value");
        assertThat(captor.getValue().accessToken()).isNull();
    }

    @Test
    void AT_헤더가_유효하면_필터가_AT를_attribute에_실어서_LogoutService에_전달한다() throws Exception {
        String accessToken = "valid-access-token";
        given(tokenAuthenticationService.authenticate(accessToken))
                .willReturn(new AuthenticatedPrincipal(5L, "USER"));
        given(logoutService.execute(any())).willReturn(LogoutResult.of(5L));

        MvcResult result = mockMvc.perform(post(LOGOUT_URI)
                        .cookie(new Cookie(REFRESH_TOKEN_COOKIE, "refresh-token-value"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andReturn();

        assertRefreshCookieCleared(result);
        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).execute(captor.capture());
        assertThat(captor.getValue().refreshToken()).isEqualTo("refresh-token-value");
        assertThat(captor.getValue().accessToken()).isEqualTo(accessToken);
    }

    @Test
    void AT_헤더가_무효하면_필터가_attribute를_비워두고_LogoutService에는_null_AT가_전달된다() throws Exception {
        String invalidAccessToken = "invalid-access-token";
        given(tokenAuthenticationService.authenticate(invalidAccessToken))
                .willThrow(new UnauthorizedException(AuthErrorCode.INVALID_TOKEN));
        given(logoutService.execute(any())).willReturn(LogoutResult.of(5L));

        MvcResult result = mockMvc.perform(post(LOGOUT_URI)
                        .cookie(new Cookie(REFRESH_TOKEN_COOKIE, "refresh-token-value"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + invalidAccessToken))
                .andExpect(status().isNoContent())
                .andReturn();

        assertRefreshCookieCleared(result);
        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).execute(captor.capture());
        assertThat(captor.getValue().refreshToken()).isEqualTo("refresh-token-value");
        assertThat(captor.getValue().accessToken()).isNull();
    }

    @Test
    void RT_쿠키도_없고_AT_헤더만_유효한_비정상_요청에도_RT_만료_쿠키가_반환된다() throws Exception {
        String accessToken = "valid-access-token";
        given(tokenAuthenticationService.authenticate(accessToken))
                .willReturn(new AuthenticatedPrincipal(5L, "USER"));
        given(logoutService.execute(any())).willReturn(LogoutResult.anonymous());

        MvcResult result = mockMvc.perform(post(LOGOUT_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andReturn();

        assertRefreshCookieCleared(result);
        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).execute(captor.capture());
        assertThat(captor.getValue().refreshToken()).isNull();
        assertThat(captor.getValue().accessToken()).isEqualTo(accessToken);
    }

    private static void assertRefreshCookieCleared(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .as("Logout 응답에는 refresh_token 쿠키 만료 지시가 포함되어야 한다")
                .isNotNull()
                .contains(REFRESH_TOKEN_COOKIE + "=")
                .contains("Max-Age=0")
                .contains("Path=/")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }
}
