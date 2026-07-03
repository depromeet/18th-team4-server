package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.security.Principal;

/**
 * AI 채팅 호출 한도 interceptor.
 * 비싼 LLM 호출(메시지 전송, 감상문 초안 생성 — POST)에만 적용되며, 초과 시 HTTP 429 를 반환한다.
 *
 * 키는 인증된 사용자(userId) 기준이다. 이 인터셉터가 붙는 경로는 전부 인증 필수(SecurityConfig)이므로
 * principal 은 항상 존재한다 — 없다면 보안 설정이 무너진 것이므로 IP 등으로 조용히 대체하지 않고
 * IllegalStateException 으로 즉시 드러낸다 (2026-06-20 429 사고의 교훈: IP 대체 동작이
 * 프론트 프록시 IP 를 키로 만들어 서로 다른 사용자들이 한 버킷을 공유했다).
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
        // 읽기(GET 등)는 한도를 소비하지 않는다 — 비싼 LLM 호출(POST)만 대상.
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String key = resolveKey(request);
        if (rateLimiter.tryConsume(key)) {
            return true;
        }

        log.info("[Guardrail] 호출 한도 초과: key={}", key);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ERROR_BODY);
        response.getWriter().flush();
        return false;
    }

    private String resolveKey(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            throw new IllegalStateException(
                    "호출 한도 대상 요청에 인증 principal 이 없음 — 보안 설정 확인 필요: " + request.getRequestURI());
        }
        return "user:" + principal.getName();
    }
}
