package com.readum.logprocessor.core;

/**
 * fingerprint 계산 입력. null 필드는 빈 값으로 취급한다.
 * topApplicationFrame: 스택트레이스 중 우리 코드(com.readum.*) 최상단 프레임 (예: "com.readum.domain.summary.SummaryJobWorker.run(SummaryJobWorker.java:42)").
 * requestUri: 경로 템플릿 권장(예: "/api/v1/books/{id}") — 가변 경로값이 fingerprint를 흩뜨리지 않도록.
 */
public record FingerprintInput(
        String exceptionClass,
        String message,
        String topApplicationFrame,
        String requestMethod,
        String requestUri
) {}
