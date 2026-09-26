package com.readum.presentation.common.security;

import com.readum.domain.auth.service.SessionAuthenticationService;
import com.readum.domain.exception.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * user_session 쿠키 인증 필터 — 쿠키를 해석해 principal 에 userId(Long) 를 채운다.
 * principal 의 모양(userId + ROLE_*)은 JwtAuthenticationFilter 와 동일하게 맞춰,
 * 아래 계층이 신원의 출처(쿠키/토큰)를 구분할 수 없게 한다.
 * 401 판정은 이 필터가 아니라 SecurityConfig 의 인가 규칙이 담당한다 — 해석 실패 시 채우지 않고 통과만 한다.
 * 소셜 로그인(JWT) 도입 시 이 필터는 게스트 인증 담당으로 남을 수 있다 — 그때 권한만 GUEST 로 바꾼다.
 */
@Slf4j
@RequiredArgsConstructor
public class SessionCookieAuthenticationFilter extends OncePerRequestFilter {

    static final String USER_SESSION_COOKIE = "user_session";
    // 소셜 로그인 도입 시 게스트 구분이 필요해지면 이 값만 "GUEST" 로 바꾼다.
    private static final String SESSION_USER_ROLE = "USER";

    private final SessionAuthenticationService sessionAuthenticationService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String userSessionId = extractSessionCookie(request);
        if (userSessionId == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Long userId = sessionAuthenticationService.authenticate(userSessionId);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + SESSION_USER_ROLE))
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (UnauthorizedException ex) {
            log.warn("세션 쿠키 검증 실패 uri={} errorCode={}",
                    request.getRequestURI(), ex.getErrorCode().name());
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }

    private String extractSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (USER_SESSION_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }
}
