package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OpenAiResponseErrorHandlerTest {

    private static final URI ANY_URI = URI.create("https://api.openai.com/v1/chat/completions");
    private static final String CHAT_MODEL = "gpt-4o-mini";

    private OpenAiResponseErrorHandler handler;
    private OpenAiRequestGate requestGate;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        requestGate = mock(OpenAiRequestGate.class);
        handler = new OpenAiResponseErrorHandler(objectMapper, requestGate, CHAT_MODEL, 300);
    }

    @Test
    void hasError_는_4xx_5xx_에서_true_2xx_에서_false() throws Exception {
        assertThat(handler.hasError(stubResponse(HttpStatus.OK, new HttpHeaders(), ""))).isFalse();
        assertThat(handler.hasError(stubResponse(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), ""))).isTrue();
        assertThat(handler.hasError(stubResponse(HttpStatus.INTERNAL_SERVER_ERROR, new HttpHeaders(), ""))).isTrue();
    }

    @Test
    void status_429_에_insufficient_quota_타입이면_AI_QUOTA_EXHAUSTED_로_분류된다() {
        String body = """
                {"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}
                """;

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), body)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED);

        // quota 소진 감지 시 게이트 쿨다운을 연다 (세 경로 공통 백오프의 단일 진입점).
        verify(requestGate).enterQuotaCooldown(eq(CHAT_MODEL), any(Duration.class));
    }

    @Test
    void status_429_의_rate_limit_타입이면_AI_RATE_LIMIT_BURST_로_분류되고_헤더가_RateLimitInfo_로_추출된다() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after", "13");
        headers.add("x-ratelimit-limit-requests", "5000");
        headers.add("x-ratelimit-remaining-requests", "0");
        headers.add("x-ratelimit-reset-requests", "12s");
        headers.add("x-ratelimit-reset-tokens", "1m30s");
        String body = """
                {"error":{"message":"Rate limit reached","type":"requests","code":"rate_limit_exceeded"}}
                """;

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, headers, body)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);
                    RateLimitInfo info = ex.getRateLimitInfo();
                    assertThat(info.retryAfter()).isEqualTo(Duration.ofSeconds(13));
                    assertThat(info.limitRequests()).isEqualTo(5000L);
                    assertThat(info.remainingRequests()).isEqualTo(0L);
                    assertThat(info.resetRequests()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(info.resetTokens()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(info.limitTokens()).isNull();
                    assertThat(info.remainingTokens()).isNull();
                });

        // burst(분당 한도)는 계정 전역 문제가 아니므로 쿨다운을 열지 않는다.
        verify(requestGate, never()).enterQuotaCooldown(anyString(), any());
    }

    @Test
    void status_429_에_body_파싱_실패_시_BURST_로_안전_폴백된다() {
        String malformedBody = "not-a-json";

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), malformedBody)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);
    }

    @Test
    void status_400_은_NonTransientAiException_으로_던져진다() {
        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.BAD_REQUEST, new HttpHeaders(), "{}")))
                .isInstanceOf(NonTransientAiException.class);
    }

    @Test
    void status_503_은_TransientAiException_으로_던져진다() {
        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.SERVICE_UNAVAILABLE, new HttpHeaders(), "{}")))
                .isInstanceOf(TransientAiException.class);
    }

    @Test
    void reset_헤더의_Go_duration_은_복합_단위도_파싱된다() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-ratelimit-reset-requests", "2m30s");
        String body = "{\"error\":{\"type\":\"requests\"}}";

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, headers, body)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(ex -> ex.getRateLimitInfo().resetRequests())
                .isEqualTo(Duration.ofSeconds(150));
    }

    @Test
    void retry_after_가_HTTP_date_형식이면_무시된다() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after", "Wed, 21 Oct 2026 07:28:00 GMT");

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, headers, "{}")))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(ex -> ex.getRateLimitInfo().retryAfter())
                .isNull();
    }

    private static ClientHttpResponse stubResponse(HttpStatus status, HttpHeaders headers, String body) {
        return new ClientHttpResponse() {
            @Override
            public HttpStatus getStatusCode() {
                return status;
            }

            @Override
            public String getStatusText() {
                return status.getReasonPhrase();
            }

            @Override
            public void close() {
            }

            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
