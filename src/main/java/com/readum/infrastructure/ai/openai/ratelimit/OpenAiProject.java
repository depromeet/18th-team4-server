package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.aiChat.out.AiAvailability;

import java.util.Locale;

/**
 * OpenAI 호출을 나누는 프로젝트. OpenAI 의 분당 한도는 키가 아니라 프로젝트 단위라,
 * 기능 간 격리는 우리가 몫을 배분하는 것이 아니라 프로젝트를 나누는 것으로 한다.
 * 프로젝트마다 API 키가 따로 있고, 공급자가 한도를 재는 단위도 프로젝트다.
 *
 * <p><b>우리가 미리 속도를 맞추지는 않는다.</b> 예전에는 프로젝트 × 모델마다 토큰 버킷을 두고 추정 토큰으로
 * 미리 걸렀지만, 그 추정은 실제 전송량과 어긋나고 한도의 진짜 값은 공급자만 안다. 지금은 공급자가 실제로
 * 돌려준 429·결제 오류를 보고 그 기능을 차단한다({@link AiAvailability}) — 추측 대신 사실로 판단한다.
 *
 * <ul>
 *   <li>{@link #CHAT}: 채팅 응답 스트리밍.</li>
 *   <li>{@link #MODERATION}: 입력·출력 검토.</li>
 *   <li>{@link #SUMMARY}: 감상문 생성.</li>
 *   <li>{@link #CONTEXT_SUMMARY}: 채팅 컨텍스트 요약.</li>
 *   <li>{@link #TITLE}: 채팅 제목 생성.</li>
 * </ul>
 */
public enum OpenAiProject {
    CHAT,
    MODERATION,
    SUMMARY,
    CONTEXT_SUMMARY,
    TITLE;

    /**
     * 이 프로젝트가 맡는 도메인 보호 단위. 프로젝트와 기능은 1:1 이라 이름이 같다 —
     * 둘이 어긋나면 호출은 A 프로젝트로 나가고 장애 상태는 B 기능에 쌓이므로, 변환을 이 한 곳에 둔다.
     */
    public AiAvailability.Capability capability() {
        return AiAvailability.Capability.valueOf(name());
    }

    /** 도메인 보호 단위에 대응하는 프로젝트. */
    public static OpenAiProject of(AiAvailability.Capability capability) {
        return valueOf(capability.name());
    }

    /** Redis 키·로그에 쓰는 소문자 이름 — 밑줄은 하이픈으로 바꾼다({@code context-summary}). */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
