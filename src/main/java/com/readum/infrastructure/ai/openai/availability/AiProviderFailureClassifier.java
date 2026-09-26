package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.ExternalApiException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.infrastructure.ai.openai.OpenAiTransientException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 호출이 남긴 예외를 "공급자 상태로 셀 실패" 로 옮긴다. 세는 기준이 흩어지면 같은 실패가 곳에 따라 다르게 집계되므로,
 * 분류는 이 한 곳만 한다.
 *
 * <p>기준은 단순하다. <b>공급자가 지금 응답을 못 주고 있는가</b> 만 센다.
 * <ul>
 *   <li>공급자 쪽 문제 — 5xx · 연결 실패 · 응답 없음 · 한도 초과 · 결제 · 키 설정</li>
 *   <li>세지 않음 — 개별 요청의 입력 오류(그 밖의 4xx), 우리 쪽 DB·변환·설정 오류,
 *       우리가 스스로 막아 던진 차단, 우리 게이트의 속도 조절 거절</li>
 * </ul>
 *
 * <p>원인 사슬을 따라 내려가며 본다 — Spring AI·WebClient 가 실제 원인을 한두 겹 감싸 던지기 때문이다.
 */
@Component
public class AiProviderFailureClassifier {

    /** 분류 결과 — 종류와, 공급자가 알려 준 재시도 시각이 있으면 그 간격. */
    public record Classification(AiProviderFailureKind kind, Duration retryAfter) {

        public static Classification of(AiProviderFailureKind kind) {
            return new Classification(kind, null);
        }
    }

    public Classification classify(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            Classification classification = classifyOne(current);
            if (classification != null) {
                return classification;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        // 알 수 없는 예외는 세지 않는다 — 우리 쪽 버그를 공급자 장애로 오인해 전 기능을 막는 쪽이 더 나쁘다.
        return Classification.of(AiProviderFailureKind.NOT_COUNTED);
    }

    private Classification classifyOne(Throwable error) {
        if (error instanceof TooManyRequestsException tooManyRequests) {
            return classifyTooManyRequests(tooManyRequests);
        }
        if (error instanceof AiDependencyUnavailableException dependencyUnavailable) {
            // 키·권한 설정 오류만 공급자 상태로 센다. 나머지는 우리가 이미 막아서 던진 것이라 다시 세면 이중 집계다.
            return isAuthError(dependencyUnavailable)
                    ? Classification.of(AiProviderFailureKind.AUTH)
                    : Classification.of(AiProviderFailureKind.NOT_COUNTED);
        }
        if (error instanceof OpenAiTransientException providerTransient) {
            // 5xx · 모델 과부하 — 공급자가 Retry-After 로 "언제 다시 오라" 고 말했으면 그 값을 차단 시간으로 쓴다.
            return new Classification(AiProviderFailureKind.TRANSIENT, providerTransient.getRetryAfter());
        }
        if (error instanceof TransientAiException) {
            return Classification.of(AiProviderFailureKind.TRANSIENT);
        }
        if (error instanceof NonTransientAiException) {
            // 429 를 뺀 4xx — 그 요청의 입력 문제라 공급자가 죽은 것이 아니다.
            return Classification.of(AiProviderFailureKind.NOT_COUNTED);
        }
        if (error instanceof ExternalApiException) {
            // 응답을 받긴 했으나 온전하지 않았다(변환 손실 의심) — 공급자 쪽 응답 문제로 센다.
            return Classification.of(AiProviderFailureKind.TRANSIENT);
        }
        if (error instanceof BusinessException) {
            // 그 밖의 도메인 예외는 우리 판단의 결과다.
            return Classification.of(AiProviderFailureKind.NOT_COUNTED);
        }
        if (error instanceof ResourceAccessException
                || error instanceof WebClientRequestException
                || error instanceof IOException
                || error instanceof TimeoutException
                || error instanceof java.net.http.HttpTimeoutException) {
            return Classification.of(AiProviderFailureKind.TRANSIENT);
        }
        return null;
    }

    private Classification classifyTooManyRequests(TooManyRequestsException error) {
        Duration retryAfter = retryAfterOf(error.getRateLimitInfo());
        if (error.getErrorCode() == AiChatErrorCode.AI_QUOTA_EXHAUSTED) {
            return new Classification(AiProviderFailureKind.QUOTA, retryAfter);
        }
        if (error.getErrorCode() == AiChatErrorCode.AI_PROVIDER_RATE_LIMITED) {
            return new Classification(AiProviderFailureKind.RATE_LIMIT, retryAfter);
        }
        // 우리 자신의 제한(사용자 메시지 폭주 가드·토큰 예산)이 거절한 것이다. 공급자는 아무 말도 하지 않았다.
        return Classification.of(AiProviderFailureKind.NOT_COUNTED);
    }

    private boolean isAuthError(AiDependencyUnavailableException error) {
        return error.getErrorCode() == AiChatErrorCode.AI_PROVIDER_AUTH_ERROR;
    }

    private Duration retryAfterOf(RateLimitInfo rateLimitInfo) {
        return rateLimitInfo == null ? null : rateLimitInfo.retryAfter();
    }
}
