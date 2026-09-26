package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiResponseErrorHandlerTest {

    private static final URI ANY_URI = URI.create("https://api.openai.com/v1/chat/completions");

    private OpenAiResponseErrorHandler handler;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        handler = new OpenAiResponseErrorHandler(objectMapper, OpenAiProject.CHAT, 300L);
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
    }

    @Test
    void status_429_의_rate_limit_타입이면_공급자_한도_초과로_분류되고_헤더가_RateLimitInfo_로_추출된다() {
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
                    // 공급자가 실제로 한 말임을 나타내는 코드 — 우리 쪽 제한의 거절과 구분된다.
                    assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.AI_PROVIDER_RATE_LIMITED);
                    RateLimitInfo info = ex.getRateLimitInfo();
                    assertThat(info.retryAfter()).isEqualTo(Duration.ofSeconds(13));
                    assertThat(info.limitRequests()).isEqualTo(5000L);
                    assertThat(info.remainingRequests()).isEqualTo(0L);
                    assertThat(info.resetRequests()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(info.resetTokens()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(info.limitTokens()).isNull();
                    assertThat(info.remainingTokens()).isNull();
                });
    }

    @Test
    void status_429_에_body_파싱_실패_시_공급자_한도_초과로_안전_폴백된다() {
        String malformedBody = "not-a-json";

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), malformedBody)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_PROVIDER_RATE_LIMITED);
    }

    @Test
    void status_401_은_공급자_인증_오류로_올려보내_그_프로젝트를_차단하게_한다() {
        // 이 요청만 다시 보내도 똑같이 막히고, 작업을 계속 최종 실패시키면 사람이 설정을 고치기 전에
        // 대기열이 모두 소진된다. 그래서 "지금 이 기능은 쓸 수 없다" 로 알린다.
        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.UNAUTHORIZED, new HttpHeaders(),
                        "{\"error\":{\"message\":\"Incorrect API key sk-secret\",\"code\":\"invalid_api_key\"}}")))
                .asInstanceOf(InstanceOfAssertFactories.type(AiDependencyUnavailableException.class))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.AI_PROVIDER_AUTH_ERROR);
                    assertThat(ex.getMessage())
                            .as("응답 본문은 조직·키 정보를 담을 수 있어 밖으로 내보내지 않는다")
                            .doesNotContain("sk-secret");
                });
    }

    @Test
    void status_403_도_공급자_인증_오류로_올려보낸다() {
        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.FORBIDDEN, new HttpHeaders(), "{}")))
                .asInstanceOf(InstanceOfAssertFactories.type(AiDependencyUnavailableException.class))
                .extracting(AiDependencyUnavailableException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_PROVIDER_AUTH_ERROR);
    }

    @Test
    void 결제_한도_코드가_실린_400_도_결제_소진으로_분류된다() {
        // 같은 뜻의 응답이 429 로도 400 으로도 온다. 상태 코드만 보면 한쪽을 "그 요청의 입력 문제" 로 오인한다.
        String body = """
                {"error":{"message":"Billing hard limit reached","type":"invalid_request_error","code":"billing_hard_limit_reached"}}
                """;

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.BAD_REQUEST, new HttpHeaders(), body)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED);
    }

    @Test
    void 응답_본문은_예외_메시지로_새어나가지_않는다() {
        String body = """
                {"error":{"message":"org-abc123 프로젝트 내부 정보","type":"invalid_request_error","code":"context_length_exceeded"}}
                """;

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.BAD_REQUEST, new HttpHeaders(), body)))
                .isInstanceOf(NonTransientAiException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain("org-abc123");
                    assertThat(ex.getMessage()).contains("context_length_exceeded");
                });
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
    void 모델_과부하_503_은_공급자가_준_재시도_간격을_실어_올린다() {
        // 차단 시간을 공급자 말과 무관한 기본값으로 잡으면, 풀리지도 않은 공급자를 일찍 두드리거나
        // 이미 살아난 공급자를 필요 이상으로 오래 막는다.
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after", "45");
        String body = """
                {"error":{"message":"The model is overloaded","type":"service_unavailable_error","code":"server_is_overloaded"}}
                """;

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.SERVICE_UNAVAILABLE, headers, body)))
                .asInstanceOf(InstanceOfAssertFactories.type(OpenAiTransientException.class))
                .satisfies(ex -> {
                    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(ex.getProviderErrorCode()).isEqualTo("server_is_overloaded");
                });
    }

    @Test
    void status_500_도_일시_오류로_올려보내고_코드를_보존한다() {
        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.INTERNAL_SERVER_ERROR, new HttpHeaders(),
                        "{\"error\":{\"code\":\"server_error\"}}")))
                .asInstanceOf(InstanceOfAssertFactories.type(OpenAiTransientException.class))
                .extracting(OpenAiTransientException::getProviderErrorCode)
                .isEqualTo("server_error");
    }

    @Test
    void slow_down_429_는_결제_문제가_아니라_속도_제한으로_분류된다() {
        // 한도 안이었는데도 요청량이 급히 늘어 거절된 경우다 — 몇십 초 뒤 다시 보내면 되므로
        // 결제 쿨다운을 열면 안 된다(열면 그 프로젝트가 5분간 통째로 막힌다).
        String body = """
                {"error":{"message":"You are sending requests too quickly","type":"rate_limit_error","code":"slow_down"}}
                """;

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), body)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_PROVIDER_RATE_LIMITED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "credit_balance_exhausted",
            "project_spend_limit_exceeded",
            "organization_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
            "billing_hard_limit_reached"
    })
    void 결제_지출_한도_코드는_모두_결제_소진으로_분류된다(String code) {
        // 다섯 코드 모두 사람이 충전하거나 한도를 올려야 풀린다 — 속도 제한과 섞으면
        // 몇십 초마다 똑같이 거절당하는 호출을 되풀이하게 된다.
        String body = "{\"error\":{\"message\":\"limit\",\"type\":\"rate_limit_error\",\"code\":\"" + code + "\"}}";

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), body)))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED);
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
    void retry_after_가_HTTP_date_형식이어도_남은_시간으로_읽는다() {
        // 이 값이 곧 차단 시간이라, 형식 하나를 못 읽어 기본값으로 떨어지면 공급자가 알려 준 시각을 버리게 된다.
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after", DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120)));

        assertThatThrownBy(() -> handler.handleError(
                ANY_URI, null, stubResponse(HttpStatus.TOO_MANY_REQUESTS, headers, "{}")))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(ex -> ex.getRateLimitInfo().retryAfter())
                .isNotNull();
    }

    @Test
    void 이미_지난_HTTP_date_는_없음으로_본다() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after", "Wed, 21 Oct 2020 07:28:00 GMT");

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
