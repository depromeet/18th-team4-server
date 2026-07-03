package com.readum.infrastructure.ai.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OpenAI 로 나가는 HTTP 클라이언트(채팅 WebClient + moderation RestClient 공용)의 연결 설정.
 *
 * 도입 배경(부하 측정): 동시 채팅이 늘면 OpenAI 로 가는 연결이 수백 개로 폭증하고, 그중 idle 로 방치돼
 * OpenAI 가 닫은 연결(stale)을 재사용하다 "Connection closed BEFORE response" 로 무더기 실패한다.
 *  - http2     : 한 연결에 여러 요청을 multiplex → 연결 수 자체를 급감시켜 stale·per-IP 리셋을 뿌리에서 줄임.
 *  - maxIdleTime: OpenAI 가 닫기 전에 우리가 먼저 idle 연결을 evict → 죽은 연결 재사용 차단.
 */
@ConfigurationProperties(prefix = "readum.openai.http-client")
public record OpenAiHttpClientProperties(
        Boolean http2,
        int maxConnections,
        Duration maxIdleTime,
        Duration maxLifeTime,
        Duration pendingAcquireTimeout
) {
    public OpenAiHttpClientProperties {
        if (http2 == null) http2 = true;
        if (maxConnections <= 0) maxConnections = 50;
        if (maxIdleTime == null) maxIdleTime = Duration.ofSeconds(5);
        if (maxLifeTime == null) maxLifeTime = Duration.ofSeconds(120);
        if (pendingAcquireTimeout == null) pendingAcquireTimeout = Duration.ofSeconds(10);
    }
}
