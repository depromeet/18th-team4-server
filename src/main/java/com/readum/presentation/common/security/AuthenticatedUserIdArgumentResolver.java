package com.readum.presentation.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class AuthenticatedUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthenticatedUserId.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            // Security 계층이 인증을 강제하므로 여기 도달 = 보안 설정 버그.
            // IP 등으로 조용히 대체하지 않고 즉시 드러낸다 (2026-06-20 429 사고의 교훈).
            throw new IllegalStateException(
                    "@AuthenticatedUserId 는 인증이 보장된 경로에서만 쓸 수 있다 — principal 이 비어 있음");
        }
        return userId;
    }
}
