package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * AI 채팅 rate limit interceptor.
 * /api/v1/ai-chat/** 엔드포인트에만 적용되며, 한도 초과 시 HTTP 429 응답을 반환한다.
 *
 * key 우선순위:
 *   1) Spring Security 의 인증 principal (Long userId 형태) — 인증 활성화 시
 *   2) X-Forwarded-For 헤더 첫 번째 IP (프록시 환경)
 *   3) ServletRequest.getRemoteAddr()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatRateLimitInterceptor implements HandlerInterceptor {

    private static final String ERROR_BODY = """
        {
            "error": {
                "message": "AI 채팅 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."
            }
        }
        """;

    private final AiChatRateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!rateLimiter.isEnabled()) {
            return true;
        }
        String key = resolveKey(request);
        if (rateLimiter.tryConsume(key)) {
            return true;
        }

        log.info("[Guardrail] rate limit exceeded: keyHash={}", Integer.toHexString(key.hashCode()));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ERROR_BODY);
        response.getWriter().flush();
        return false;
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() != null
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "user:" + authentication.getPrincipal();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",", 2)[0].trim();
        }
        String remote = request.getRemoteAddr();
        return "ip:" + (remote == null ? "unknown" : remote);
    }
}
