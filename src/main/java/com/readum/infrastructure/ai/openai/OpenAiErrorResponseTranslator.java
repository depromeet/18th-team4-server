package com.readum.infrastructure.ai.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI 오류 응답 하나를 "우리 도메인이 아는 실패" 로 옮긴다. 상태 코드 · 응답 헤더 · 본문의 오류 코드를
 * 함께 보아야 <b>공급자가 지금 응답을 못 주는 상황</b> 과 <b>이 요청 하나가 잘못된 것</b> 을 나눌 수 있다.
 *
 * <p><b>왜 전송 방식에서 떼어 냈는가.</b> 같은 프로젝트의 같은 오류가 전송 방식에 따라 다르게 분류되면,
 * 장애 판정이 "어느 경로로 나갔는지" 에 좌우된다. 실제로 그런 일이 있었다 — Spring AI 의 {@code OpenAiApi} 는
 * 오류 핸들러를 {@code RestClient} 에만 연결하고 {@code WebClient} 에는 붙이지 않으므로
 * (2.0.0-M4 바이트코드 확인), 스트리밍 경로의 429·401·503 은 이 분류를 거치지 않고
 * {@code WebClientResponseException} 으로 올라가 "세지 않는 실패" 로 묻혔다.
 * 그래서 분류를 이 한 곳에 두고, 단발 호출({@link OpenAiResponseErrorHandler})과
 * 스트리밍({@link OpenAiStreamingErrorFilter})이 같은 것을 쓴다.
 *
 * <p><b>프로젝트마다 하나씩 만든다.</b> 결제·지출 한도 쿨다운이 "프로젝트 × 모델" 키에 쌓이므로,
 * 공용 하나를 여러 경로가 나눠 쓰면 제목 생성에서 본 오류가 채팅의 상태를 바꾼다.
 *
 * <p><b>응답 본문은 클라이언트로 나가지 않는다.</b> 본문에는 조직·프로젝트 식별자나 키 일부가 실릴 수 있어
 * 로그에만 남기고, 예외 메시지에는 상태 코드와 오류 종류·코드만 담는다.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenAiErrorResponseTranslator {

    private static final Pattern GO_DURATION_TOKEN = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)");

    /** RFC 9110 의 Retry-After 는 정수 초 또는 HTTP-date 다. 후자를 위한 파서(RFC 1123). */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

    private final ObjectMapper objectMapper;
    private final OpenAiProject project;
    private final long authBlockSeconds;

    /**
     * 변환 결과 — 올려보낼 예외 하나다.
     *
     * <p>예전에는 여기에 "이 프로젝트를 얼마나 막아 둘 것인가" 가 함께 실려, 호출 지점이 그것을 별도 Redis 키에
     * 따로 적었다. 그 키는 장애 차단기가 여는 차단과 같은 계기(결제·지출 한도 오류)로 열리고 같은 대상을 막는
     * 두 번째 장치였고, 둘의 해제 조건이 달라(한쪽은 시간, 한쪽은 실제 호출 성공) 상태가 갈렸다.
     * 지금은 이 예외가 그대로 차단기까지 올라가 <b>한 곳</b>에서 차단을 연다 —
     * 종류별 차단 시간은 {@code openai.availability.*-block-seconds} 가, 공급자가 준 Retry-After 존중은
     * 차단기가 맡는다.
     *
     * @param failure 올려보낼 예외
     */
    public record Translation(RuntimeException failure) {
    }

    /**
     * 응답을 상태 코드와 {@code error.code} 로 갈라 분류한다. 갈라지는 자리는 다섯이다.
     *
     * <ol>
     *   <li><b>결제·지출 한도</b>(429 의 여섯 코드) — 시간이 지났다고 풀리지 않는다. 프로젝트 쿨다운을 열고
     *       가장 길게 차단한다. 이 판정은 상태 코드보다 <b>코드가 먼저</b>다 — 같은 429 안에 섞여 있기 때문이다.</li>
     *   <li><b>속도 제한</b>(그 밖의 429 — {@code slow_down} 과 코드 없는 한도 초과) — 몇십 초 뒤 다시 보내면 된다.</li>
     *   <li><b>키·권한</b>(401 · 403) — 사람이 설정을 고쳐야 풀린다.</li>
     *   <li><b>그 밖의 4xx</b> — 그 요청 하나의 입력 문제라 다시 보내도 같다. 공급자 상태로 세지 않는다.</li>
     *   <li><b>5xx · 과부하</b>(500 · 503 {@code server_is_overloaded}) — 공급자 쪽 일시 문제.
     *       Retry-After 를 실어 올려 차단 시간이 공급자 말과 어긋나지 않게 한다.</li>
     * </ol>
     */
    public Translation translate(HttpStatusCode statusCode, HttpHeaders headers, String body) {
        OpenAiErrorBody parsed = parseErrorBody(body);
        String type = typeOf(parsed);
        String code = codeOf(parsed);

        if (OpenAiProviderErrorCodes.isSpendLimited(type, code)) {
            return spendLimited(body, headers, code);
        }
        if (statusCode.value() == 429) {
            return new Translation(rateLimited(body, headers, code));
        }
        if (statusCode.value() == 401 || statusCode.value() == 403) {
            return new Translation(authFailure(statusCode, body));
        }

        String message = redactedMessage(statusCode, parsed);
        if (statusCode.is4xxClientError()) {
            log.warn("OpenAI 요청 거절 project={} status={} body={}", project.key(), statusCode.value(), body);
            return new Translation(new NonTransientAiException(message));
        }
        // 5xx — 과부하(503)도 여기 든다. 둘의 처방이 같아서(잠시 뒤 다시) 종류를 나누지 않고,
        // 공급자가 준 Retry-After 와 코드만 실어 올린다.
        Duration retryAfter = parseRetryAfter(headers == null ? null : headers.getFirst("retry-after"));
        if (OpenAiProviderErrorCodes.isOverloaded(type, code)) {
            log.warn("OpenAI 모델 과부하 project={} status={} retryAfter={} body={}",
                    project.key(), statusCode.value(), retryAfter, body);
        } else {
            log.warn("OpenAI 일시 오류 project={} status={} retryAfter={} body={}",
                    project.key(), statusCode.value(), retryAfter, body);
        }
        return new Translation(new OpenAiTransientException(message, retryAfter, code));
    }

    /**
     * 결제·지출 한도로 막혔다. 곧 풀릴 종류가 아니므로 그 프로젝트를 오래 막아야 하는데, 그 차단은
     * 이 예외를 받은 장애 차단기가 연다 — 공급자가 Retry-After 를 줬으면 그 값을, 없으면
     * {@code openai.availability.quota-block-seconds} 를 쓴다.
     *
     * <p>그 시간이 지난 것이 "해결됐다" 는 뜻은 아니다 — 차단을 푸는 것은 전용 복구 확인의 실제 호출이
     * 성공했을 때뿐이다. 그래서 여기서 별도의 시간제 쿨다운을 따로 적지 않는다.
     */
    private Translation spendLimited(String body, HttpHeaders headers, String code) {
        RateLimitInfo rateLimitInfo = extractRateLimitInfo(headers);
        log.error("OpenAI 결제·지출 한도 project={} code={} body={}", project.key(), code, body);
        return new Translation(
                new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED, rateLimitInfo));
    }

    /**
     * 공급자가 속도 제한으로 거절했다({@code slow_down} 또는 코드 없는 요청·토큰 한도 초과).
     * 이 한 건만으로 그 기능을 차단한다 — 공급자가 "지금은 받을 수 없다" 고 직접 말한 것이라,
     * 여러 건이 쌓이기를 기다릴 이유가 없다. 차단 시간은 공급자가 준 Retry-After 를 존중한다.
     */
    private TooManyRequestsException rateLimited(String body, HttpHeaders headers, String code) {
        RateLimitInfo rateLimitInfo = extractRateLimitInfo(headers);
        log.warn("OpenAI 속도 제한 응답 project={} code={} body={}", project.key(), code, body);
        return new TooManyRequestsException(AiChatErrorCode.AI_PROVIDER_RATE_LIMITED, rateLimitInfo);
    }

    private String typeOf(OpenAiErrorBody parsed) {
        return parsed == null ? null : parsed.type();
    }

    private String codeOf(OpenAiErrorBody parsed) {
        return parsed == null ? null : parsed.code();
    }

    /**
     * 키·권한 설정 문제. 이 요청만 다시 보내 봐야 똑같이 막히고, 그렇다고 작업을 계속 최종 실패시키면
     * 사람이 설정을 고치기 전에 대기열이 모두 소진된다. 그래서 "지금 이 기능은 쓸 수 없다" 로 올려보내
     * 그 프로젝트를 차단하게 한다.
     */
    private AiDependencyUnavailableException authFailure(HttpStatusCode statusCode, String body) {
        log.error("OpenAI 인증·권한 오류 project={} status={} body={}", project.key(), statusCode.value(), body);
        return new AiDependencyUnavailableException(AiChatErrorCode.AI_PROVIDER_AUTH_ERROR, authBlockSeconds);
    }

    /** 클라이언트·저장 기록에 남을 메시지 — 상태 코드와 오류 종류·코드까지만. 본문 원문은 로그에만 남는다. */
    private String redactedMessage(HttpStatusCode statusCode, OpenAiErrorBody parsed) {
        String type = (parsed == null || parsed.type() == null) ? "unknown" : parsed.type();
        String code = (parsed == null || parsed.code() == null) ? "none" : parsed.code();
        return "OpenAI %d (project=%s, type=%s, code=%s)".formatted(
                statusCode.value(), project.key(), type, code);
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

    /**
     * Retry-After 는 정수 초 또는 HTTP-date 다(RFC 9110). OpenAI 는 지금까지 정수 초만 보냈지만,
     * 이 값이 차단 시간을 정하므로 형식 하나를 못 읽어 기본값으로 떨어지는 일이 없게 둘 다 읽는다.
     * 이미 지난 시각이면 0 이 아니라 없음으로 본다 — "지금 바로 다시" 는 차단 시간으로 쓸 수 없다.
     */
    private Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (NumberFormatException notSeconds) {
            return parseRetryAfterDate(trimmed);
        }
    }

    private Duration parseRetryAfterDate(String value) {
        try {
            Duration until = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(value, HTTP_DATE));
            return (until.isZero() || until.isNegative()) ? null : until;
        } catch (DateTimeParseException notADate) {
            log.debug("Retry-After 파싱 실패 - value={}", value);
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
