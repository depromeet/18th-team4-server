package com.readum.presentation.common.security;

import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Slf4j
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public JwtAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) {
        log.warn("접근 거부 uri={}", request.getRequestURI());
        handlerExceptionResolver.resolveException(
                request, response, null, new ForbiddenException(AuthErrorCode.FORBIDDEN)
        );
    }
}
