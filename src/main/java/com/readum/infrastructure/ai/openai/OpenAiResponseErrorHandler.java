package com.readum.infrastructure.ai.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Spring AI 기본 핸들러(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER) 는 4xx/5xx 를
// NonTransientAiException/TransientAiException 으로 일괄 wrap 하면서 HTTP status / 응답 헤더 /
// OpenAI 응답 body 의 error.type 정보를 모두 메시지 문자열에 묻어 버린다.
//
// 이 핸들러는 OpenAiApi 의 RestClient/WebClient 양쪽에 주입되어, 가장 낮은 계층에서
// 도메인 예외(429 burst vs quota 소진) 로 분류하고 X-RateLimit-* / Retry-After 헤더를
// RateLimitInfo 로 추출해 예외에 동봉한다 (Issue #30 인수조건).
@Slf4j
@RequiredArgsConstructor
public class OpenAiResponseErrorHandler implements ResponseErrorHandler {

    private static final String OPENAI_ERROR_TYPE_INSUFFICIENT_QUOTA = "insufficient_quota";

    // OpenAI 가 reset 헤더에 사용하는 Go duration 포맷 (예: "1m30s", "12s", "500ms").
    // ms / s / m / h 단위 조합 지원. parseGoDuration() 참조.
    private static final Pattern GO_DURATION_TOKEN = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)");

    private final ObjectMapper objectMapper;

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        HttpStatusCode statusCode = response.getStatusCode();
        HttpHeaders headers = response.getHeaders();
        String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);

        if (statusCode.value() == 429) {
            throw classifyRateLimit(body, headers);
        }

        // 429 외 4xx 는 NonTransient (재시도 무의미), 5xx 는 Transient (재시도 가능).
        // Spring AI 의 retry/observation 파이프라인이 두 타입을 보고 정책을 결정한다.
        String message = "%s - %s".formatted(statusCode.value(), body);
        if (statusCode.is4xxClientError()) {
            throw new NonTransientAiException(message);
        }
        throw new TransientAiException(message);
    }

    private TooManyRequestsException classifyRateLimit(String body, HttpHeaders headers) {
        RateLimitInfo rateLimitInfo = extractRateLimitInfo(headers);
        OpenAiErrorBody parsed = parseErrorBody(body);
        boolean isQuotaExhausted = parsed != null
                && OPENAI_ERROR_TYPE_INSUFFICIENT_QUOTA.equals(parsed.type());

        if (isQuotaExhausted) {
            log.error("OpenAI quota 소진 (insufficient_quota) - body={}", body);
            return new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED, rateLimitInfo);
        }
        log.warn("OpenAI Rate limit burst - body={}", body);
        return new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, rateLimitInfo);
    }

    private RateLimitInfo extractRateLimitInfo(HttpHeaders headers) {
        if (headers == null) {
            return RateLimitInfo.empty();
        }
        return new RateLimitInfo(
                parseRetryAfter(headers.getFirst("retry-after")),
                parseLong(headers.getFirst("x-ratelimit-limit-requests")),
                parseLong(headers.getFirst("x-ratelimit-limit-tokens")),
                parseLong(headers.getFirst("x-ratelimit-remaining-requests")),
                parseLong(headers.getFirst("x-ratelimit-remaining-tokens")),
                parseGoDuration(headers.getFirst("x-ratelimit-reset-requests")),
                parseGoDuration(headers.getFirst("x-ratelimit-reset-tokens"))
        );
    }

    // RFC 9110 의 Retry-After 는 정수 초 또는 HTTP-date 형식. OpenAI 는 정수 초만 사용해서 그것만 처리.
    private Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            // HTTP-date 형식 (RFC 1123) 은 OpenAI 에서 보지 못해서 일단 무시.
            log.debug("Retry-After 파싱 실패 (HTTP-date 형식 미지원) - value={}", value);
            return null;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // OpenAI 의 reset 헤더는 Go duration 포맷 ("1m30s", "12s", "500ms").
    // 정확히 해석하려면 단위별 파싱 필요. 잘못된 포맷이면 null 반환 후 호출측에서 폴백.
    private Duration parseGoDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = GO_DURATION_TOKEN.matcher(value.trim());
        Duration accumulated = Duration.ZERO;
        boolean matched = false;
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() != lastEnd) {
                // 토큰 사이에 알 수 없는 문자가 있으면 포맷 오류로 간주
                return null;
            }
            double amount = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2);
            accumulated = accumulated.plus(toDuration(amount, unit));
            lastEnd = matcher.end();
            matched = true;
        }
        if (!matched || lastEnd != value.trim().length()) {
            return null;
        }
        return accumulated;
    }

    private Duration toDuration(double amount, String unit) {
        return switch (unit) {
            case "ms" -> Duration.ofNanos((long) (amount * 1_000_000));
            case "s" -> Duration.ofNanos((long) (amount * 1_000_000_000));
            case "m" -> Duration.ofNanos((long) (amount * 60L * 1_000_000_000));
            case "h" -> Duration.ofNanos((long) (amount * 3_600L * 1_000_000_000));
            default -> Duration.ZERO;
        };
    }

    private OpenAiErrorBody parseErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            OpenAiErrorWrapper wrapper = objectMapper.readValue(body, OpenAiErrorWrapper.class);
            return wrapper == null ? null : wrapper.error();
        } catch (RuntimeException ex) {
            log.debug("OpenAI error body 파싱 실패 - body={}", body, ex);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenAiErrorWrapper(OpenAiErrorBody error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenAiErrorBody(String type, String code, String message) {}
}
