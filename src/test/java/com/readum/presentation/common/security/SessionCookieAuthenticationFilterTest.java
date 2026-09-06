package com.readum.presentation.common.security;

import com.readum.domain.auth.service.SessionAuthenticationService;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SessionCookieAuthenticationFilterTest {

    @Mock
    private SessionAuthenticationService sessionAuthenticationService;

    private SessionCookieAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SessionCookieAuthenticationFilter(sessionAuthenticationService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 유효한_세션_쿠키면_principal_에_userId_가_채워진다() throws Exception {
        request.setCookies(new Cookie(SessionCookieAuthenticationFilter.USER_SESSION_COOKIE, "valid-session-id"));
        given(sessionAuthenticationService.authenticate("valid-session-id")).willReturn(7L);

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(7L);
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        assertThat(chain.getRequest()).as("체인이 계속 진행되어야 한다").isNotNull();
    }

    @Test
    void 쿠키가_없으면_principal_을_채우지_않고_통과한다() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(sessionAuthenticationService);
    }

    @Test
    void 무효한_세션이면_principal_을_비운_채_통과한다() throws Exception {
        request.setCookies(new Cookie(SessionCookieAuthenticationFilter.USER_SESSION_COOKIE, "invalid-session-id"));
        given(sessionAuthenticationService.authenticate("invalid-session-id"))
                .willThrow(new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 이미_JWT_인증이_되어_있으면_세션_해석을_시도하지_않는다() throws Exception {
        UsernamePasswordAuthenticationToken existing = new UsernamePasswordAuthenticationToken(
                5L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(existing);
        request.setCookies(new Cookie(SessionCookieAuthenticationFilter.USER_SESSION_COOKIE, "valid-session-id"));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
        verifyNoInteractions(sessionAuthenticationService);
    }
}
