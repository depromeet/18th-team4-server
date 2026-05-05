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
 *   2) ServletRequest.getRemoteAddr()
 *
 * 클라이언트가 임의로 설정 가능한 X-Forwarded-For 헤더는 신뢰하지 않는다.
 * (현 인프라는 단일 EC2 + nginx 직결이라 getRemoteAddr() 가 실제 클라이언트 IP 다.)
 * 향후 ALB / CDN 등 신뢰된 프록시 뒤로 들어가게 되면 Spring 의
 * `server.forward-headers-strategy: native` 설정으로 위임하여 인프라 레벨에서 처리한다.
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
        String remote = request.getRemoteAddr();
        return "ip:" + (remote == null ? "unknown" : remote);
    }
}
