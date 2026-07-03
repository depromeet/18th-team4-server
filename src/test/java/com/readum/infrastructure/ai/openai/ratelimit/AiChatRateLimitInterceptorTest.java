package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatRateLimitInterceptorTest {

    @Mock
    private AiChatRateLimiter rateLimiter;

    private AiChatRateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AiChatRateLimitInterceptor(rateLimiter);
        request = new MockHttpServletRequest("POST", "/api/v1/ai-chat/sessions/1/messages");
        response = new MockHttpServletResponse();
    }

    private void authenticateAs(long userId) {
        request.setUserPrincipal(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void 한도_키는_사용자_userId_기준이다() throws Exception {
        given(rateLimiter.isEnabled()).willReturn(true);
        given(rateLimiter.tryConsume(anyString())).willReturn(true);
        authenticateAs(7L);

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("user:7");
    }

    @Test
    void 같은_IP_라도_사용자가_다르면_다른_키로_소비한다() throws Exception {
        // 6/20 사고 재현 방지: 프록시가 IP 를 합쳐도 사용자별 버킷이 분리되어야 한다.
        given(rateLimiter.isEnabled()).willReturn(true);
        given(rateLimiter.tryConsume(anyString())).willReturn(true);

        request.setRemoteAddr("13.236.146.104");
        authenticateAs(1L);
        interceptor.preHandle(request, response, new Object());

        MockHttpServletRequest secondRequest =
                new MockHttpServletRequest("POST", "/api/v1/ai-chat/sessions/2/messages");
        secondRequest.setRemoteAddr("13.236.146.104");
        secondRequest.setUserPrincipal(new UsernamePasswordAuthenticationToken(
                2L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        interceptor.preHandle(secondRequest, response, new Object());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(keyCaptor.capture());
        assertThat(keyCaptor.getAllValues()).containsExactly("user:1", "user:2");
    }

    @Test
    void principal_이_없으면_IP_로_대체하지_않고_설정_버그로_드러낸다() throws Exception {
        given(rateLimiter.isEnabled()).willReturn(true);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(IllegalStateException.class);
        verify(rateLimiter, never()).tryConsume(anyString());
    }

    @Test
    void POST_가_아닌_요청은_한도를_소비하지_않는다() throws Exception {
        given(rateLimiter.isEnabled()).willReturn(true);
        MockHttpServletRequest getRequest =
                new MockHttpServletRequest("GET", "/api/v1/ai-chat/sessions/1/messages");

        boolean allowed = interceptor.preHandle(getRequest, response, new Object());

        assertThat(allowed).isTrue();
        verify(rateLimiter, never()).tryConsume(anyString());
    }

    @Test
    void 한도_초과면_429_와_에러_본문을_반환한다() throws Exception {
        given(rateLimiter.isEnabled()).willReturn(true);
        given(rateLimiter.tryConsume(anyString())).willReturn(false);
        authenticateAs(7L);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("호출 한도");
    }

    @Test
    void enabled_가_false_면_무조건_통과한다() throws Exception {
        given(rateLimiter.isEnabled()).willReturn(false);

        boolean proceeded = interceptor.preHandle(request, response, new Object());

        assertThat(proceeded).isTrue();
    }
}
