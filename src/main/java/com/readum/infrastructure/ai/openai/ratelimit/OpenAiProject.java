package com.readum.infrastructure.ai.openai.ratelimit;

import java.util.Locale;

/**
 * OpenAI 호출을 나누는 프로젝트. OpenAI 의 분당 한도는 키가 아니라 프로젝트 단위라,
 * 기능 간 격리는 우리가 몫을 배분하는 것이 아니라 프로젝트를 나누는 것으로 한다.
 * 프로젝트마다 API 키와 한도가 따로 있고, 전역 게이트의 토큰 버킷도 프로젝트마다 따로 돈다.
 *
 * <ul>
 *   <li>{@link #CHAT}: 채팅 응답 스트리밍 — 사용자가 기다리는 경로라 버킷 자리를 짧게 기다려 준다.</li>
 *   <li>{@link #MODERATION}: 입력 moderation — 키만 분리하고 게이트는 거치지 않는다.</li>
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

    /** Redis 키·로그에 쓰는 소문자 이름 — 밑줄은 하이픈으로 바꾼다({@code context-summary}). */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
