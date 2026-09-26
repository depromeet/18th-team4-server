package com.readum.infrastructure.ai.openai;

import java.util.Set;

/**
 * OpenAI 오류 응답의 {@code error.type} · {@code error.code} 문자열과, 그 값으로 갈리는 판정.
 * 출처는 공급자의 오류 코드 안내다(https://developers.openai.com/api/docs/guides/error-codes).
 *
 * <p><b>왜 한 곳에 모으는가.</b> 같은 429 안에 "잠시 빠르게 보냈다" 와 "돈이 없다" 가 섞여 있고, 이 둘의
 * 처방은 정반대다 — 앞은 몇십 초 뒤 다시 보내면 되고, 뒤는 사람이 결제나 한도를 고칠 때까지 다시 보내면
 * 똑같이 거절당한다. 문자열 비교가 여러 곳에 흩어지면 한 곳만 빠뜨려도 그 구분이 무너진다.
 */
public final class OpenAiProviderErrorCodes {

    /**
     * 결제·지출 한도로 막힌 것들. <b>시간이 지났다는 것만으로 풀렸다고 볼 수 없는</b> 종류다 —
     * 선불 잔액이 0 이거나, 프로젝트·조직에 걸린 월 지출 한도나 사용량 한도에 닿았다는 뜻이라
     * 사람이 충전하거나 한도를 올려야 풀린다. 그래서 길게 차단하고, 실제 호출이 성공할 때만 정상으로 되돌린다.
     */
    private static final Set<String> SPEND_LIMIT_CODES = Set.of(
            "insufficient_quota",              // 계정에 남은 사용 가능액이 없다
            "billing_hard_limit_reached",      // 결제 상한에 닿았다
            "credit_balance_exhausted",        // 선불 크레딧을 다 썼다
            "project_spend_limit_exceeded",    // 프로젝트 월 지출 한도 초과
            "organization_spend_limit_exceeded",  // 조직 월 지출 한도 초과
            "organization_usage_limit_exceeded"   // 조직에 배정된 사용량 한도 초과
    );

    // 결제·지출 한도가 아닌 429 는 전부 속도 제한으로 다룬다 — 공급자가 "속도를 줄여라" 고 말한
    // slow_down 과, 코드 없이 오는 요청·토큰 한도 초과가 여기 든다. 둘의 처방이 같아서(Retry-After 만큼
    // 기다린 뒤 다시) 코드를 따로 볼 이유가 없다. 결제·지출 한도만 갈라내면 충분하다.

    /** 503 — 모델이 일시적으로 과부하라는 뜻. 5xx 와 같은 일시 실패로 다루고 Retry-After 를 존중한다. */
    private static final Set<String> OVERLOADED_CODES = Set.of(
            "server_is_overloaded", "service_unavailable_error");

    private OpenAiProviderErrorCodes() {
    }

    /** 결제·지출 한도로 막힌 응답인가. {@code type} 과 {@code code} 어느 쪽에 실려 와도 같게 본다. */
    public static boolean isSpendLimited(String type, String code) {
        return contains(SPEND_LIMIT_CODES, type) || contains(SPEND_LIMIT_CODES, code);
    }

    /** 모델 과부하(503)인가. */
    public static boolean isOverloaded(String type, String code) {
        return contains(OVERLOADED_CODES, type) || contains(OVERLOADED_CODES, code);
    }

    /**
     * 본문에 {@code type} 이나 {@code code} 가 실리지 않은 응답이 흔하다(본문이 비어 있거나 파싱에 실패한 경우).
     * {@code Set.of} 로 만든 집합은 {@code contains(null)} 에서 터지므로 없음을 먼저 걸러 낸다.
     */
    private static boolean contains(Set<String> codes, String value) {
        return value != null && codes.contains(value);
    }
}
