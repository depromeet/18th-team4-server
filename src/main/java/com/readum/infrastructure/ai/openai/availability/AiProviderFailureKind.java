package com.readum.infrastructure.ai.openai.availability;

/**
 * 실패를 "공급자 상태" 로 셀 때의 구분. 무엇을 세느냐가 차단 판단을 좌우하므로, 분류는 한 곳에서만 한다
 * ({@link AiProviderFailureClassifier}).
 */
public enum AiProviderFailureKind {

    /** 5xx · 연결 실패 · 응답 없음(기한 초과) — 여러 건이 이어질 때만 차단한다. */
    TRANSIENT(true, false),

    /** 429 중 한도 초과(결제 문제 아님) — 한 건으로 바로 차단하고, 공급자가 준 Retry-After 를 존중한다. */
    RATE_LIMIT(true, true),

    /** 결제·잔액 소진(insufficient_quota 등) — 한 건으로 바로, 오래 차단한다. 곧 풀릴 종류가 아니다. */
    QUOTA(true, true),

    /** 401 · 403 — 키·권한 설정 문제다. 작업을 계속 최종 실패시키는 대신 그 프로젝트를 오래 차단한다. */
    AUTH(true, true),

    /** 개별 요청의 입력 오류(그 밖의 4xx) · 우리 쪽 내부 오류 — 공급자 상태로 세지 않는다. */
    NOT_COUNTED(false, false);

    private final boolean counted;
    private final boolean immediate;

    AiProviderFailureKind(boolean counted, boolean immediate) {
        this.counted = counted;
        this.immediate = immediate;
    }

    /** 공급자 상태 집계 대상인가. */
    public boolean counted() {
        return counted;
    }

    /** 한 건만으로 바로 차단하는 종류인가(누적 기준을 기다리지 않는다). */
    public boolean immediate() {
        return immediate;
    }
}
