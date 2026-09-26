package com.readum.infrastructure.ai.openai;

import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;

/**
 * 공급자 쪽 일시 실패(5xx · 과부하)를, 원인을 잃지 않은 채로 올려보내는 예외.
 *
 * <p><b>왜 새로 두는가.</b> Spring AI 기본 분류는 5xx 를 {@link TransientAiException} 하나로 묶으면서
 * 응답 헤더를 메시지 문자열에 묻어 버린다. 그러면 공급자가 {@code Retry-After} 로 알려 준 "언제 다시 오라" 를
 * 우리가 쓸 수 없어, 차단 시간을 공급자 말과 무관한 기본값으로 잡게 된다.
 *
 * <p>{@link TransientAiException} 을 <b>상속</b>하는 이유는 Spring AI 의 재시도·관측이 보는 타입을
 * 그대로 두기 위해서다 — 새 타입을 별도 계층으로 만들면 그쪽 정책에서 이 실패가 빠진다.
 *
 * @param retryAfter        공급자가 알려 준 다음 시도까지의 간격. 없으면 {@code null}
 * @param providerErrorCode 응답 본문의 {@code error.code}(없으면 {@code null}) — 운영에서 원인을 되짚는 실마리
 */
public class OpenAiTransientException extends TransientAiException {

    private final Duration retryAfter;
    private final String providerErrorCode;

    public OpenAiTransientException(String message, Duration retryAfter, String providerErrorCode) {
        super(message);
        this.retryAfter = retryAfter;
        this.providerErrorCode = providerErrorCode;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public String getProviderErrorCode() {
        return providerErrorCode;
    }
}
