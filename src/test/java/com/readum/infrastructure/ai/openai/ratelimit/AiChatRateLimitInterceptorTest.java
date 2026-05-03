package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@Tag("guardrail")
class AiChatRateLimitInterceptorTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 한도_내_요청은_통과한다() throws Exception {
        AiChatRateLimiter limiter = mock(AiChatRateLimiter.class);
        given(limiter.isEnabled()).willReturn(true);
        given(limiter.tryConsume(any())).willReturn(true);
        AiChatRateLimitInterceptor interceptor = new AiChatRateLimitInterceptor(limiter);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-chat/stream");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceeded = interceptor.preHandle(request, response, new Object());

        assertThat(proceeded).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 한도_초과시_429_응답을_반환하고_체인을_중단한다() throws Exception {
        AiChatRateLimiter limiter = mock(AiChatRateLimiter.class);
        given(limiter.isEnabled()).willReturn(true);
        given(limiter.tryConsume(any())).willReturn(false);
        AiChatRateLimitInterceptor interceptor = new AiChatRateLimitInterceptor(limiter);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-chat/stream");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceeded = interceptor.preHandle(request, response, new Object());

        assertThat(proceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("AI 채팅 호출 한도를 초과했습니다");
    }

    @Test
    void enabled_가_false_면_무조건_통과한다() throws Exception {
        AiChatRateLimiter limiter = mock(AiChatRateLimiter.class);
        given(limiter.isEnabled()).willReturn(false);
        AiChatRateLimitInterceptor interceptor = new AiChatRateLimitInterceptor(limiter);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceeded = interceptor.preHandle(request, response, new Object());

        assertThat(proceeded).isTrue();
    }

    @Test
    void 인증_principal_이_있으면_user_key_를_사용한다() throws Exception {
        AiChatRateLimiter limiter = mock(AiChatRateLimiter.class);
        given(limiter.isEnabled()).willReturn(true);
        given(limiter.tryConsume("user:42")).willReturn(true);
        AiChatRateLimitInterceptor interceptor = new AiChatRateLimitInterceptor(limiter);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of())
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceeded = interceptor.preHandle(request, response, new Object());

        assertThat(proceeded).isTrue();
    }
}
