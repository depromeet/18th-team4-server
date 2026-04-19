package com.readum.presentation.common.security;

import com.readum.domain.auth.dto.AuthenticatedPrincipal;
import com.readum.domain.auth.service.TokenAuthenticationService;
import com.readum.domain.exception.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_EXCEPTION_ATTRIBUTE = "authException";
    public static final String ACCESS_TOKEN_ATTRIBUTE = "accessToken";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenAuthenticationService tokenAuthenticationService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String accessToken = extractToken(request);
        if (accessToken == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedPrincipal principal = tokenAuthenticationService.authenticate(accessToken);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal.userId(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(ACCESS_TOKEN_ATTRIBUTE, accessToken);
        } catch (UnauthorizedException ex) {
            SecurityContextHolder.clearContext();
            request.setAttribute(AUTH_EXCEPTION_ATTRIBUTE, ex);
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
