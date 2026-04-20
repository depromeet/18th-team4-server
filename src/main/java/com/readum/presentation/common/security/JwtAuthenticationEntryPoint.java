package com.readum.presentation.common.security;

import tools.jackson.databind.ObjectMapper;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.presentation.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {

        Object raw = request.getAttribute(JwtAuthenticationFilter.AUTH_EXCEPTION_ATTRIBUTE);
        ErrorCode errorCode = raw instanceof UnauthorizedException authException
                ? authException.getErrorCode()
                : ErrorCode.UNAUTHORIZED;

        log.warn("인증 실패 uri={} errorCode={}", request.getRequestURI(), errorCode.name());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                new ApiResponse<>(null, new ApiResponse.ErrorBody(errorCode.getMessage()))
        );
    }
}
