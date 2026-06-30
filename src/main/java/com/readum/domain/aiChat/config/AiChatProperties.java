package com.readum.domain.aiChat.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 잘못된 yml 값(0 / 음수) 으로 인한 런타임 오류를 부팅 시점에 차단하기 위해 @Validated.
// nested record 에는 @Valid 로 전파해야 안쪽 필드의 @Positive 가 검증된다.
@Validated
@ConfigurationProperties(prefix = "ai-chat")
public record AiChatProperties(
        @Valid ContextWindow contextWindow,
        @Valid MessageRule message,
        @Valid RateLimit rateLimit,
        @Valid TitleGeneration titleGeneration
) {

    /**
     * 1턴 = USER 메시지 1 + ASSISTANT 메시지 1.
     */
    public record ContextWindow(@Positive int maxTurns) {

        public int maxMessages() {
            return maxTurns * 2;
        }
    }

    public record MessageRule(@Positive int maxContentLength) {
    }

    /**
     * 사용자별 호출 한도. 정밀 정책은 추후 도입 예정이고, 현재는 OpenAI 비용 폭주
     * (클라이언트 무한 retry, 키 유출) 방어 용도다.
     * 정상/거부 카운터를 분리한다 — moderation false-positive 가 폭증해도 정상 메시지 카운트는 0 이라
     * 정상 채팅이 막히지 않고, 어뷰즈(의도적 거부 입력 반복)만 거부 카운터로 차단된다.
     * 정책:
     * - 정상: 최근 countPeriodSeconds 초 안에 COMPLETED USER 메시지가 maxMessageCount 회 이상이면 429.
     * - 거부: 최근 rejectedCountPeriodSeconds 초 안에 REJECTED USER 메시지가 rejectedMaxMessageCount 회 이상이면 429.
     *   거부 한도는 운영 데이터가 없으므로 정상보다 충분히 큰 시간창·횟수의 보수적 시작값으로 둔다.
     */
    public record RateLimit(
            @Positive int countPeriodSeconds,
            @Positive int maxMessageCount,
            @Positive int rejectedCountPeriodSeconds,
            @Positive int rejectedMaxMessageCount
    ) {
    }

    /**
     * 제목 생성 전용 스케줄러 크기.
     * 제목 생성은 사용자 응답 경로 밖에서 도는 백그라운드 작업이라 latency 가 중요하지 않다.
     * 영속화(Done 이벤트 직전의 짧은 JDBC)가 쓰는 전역 boundedElastic 과 같은 풀을 쓰면,
     * 첫 메시지가 몰릴 때 느린 제목 생성 LLM 호출이 스레드를 다 점유해 영속화가 큐에서 밀린다(격벽 부재).
     * 그래서 전용 풀로 분리하고:
     * - threadCap 은 작게  : 동시 제목 생성 = 빌려 쓰는 OpenAI 연결·DB 커넥션 수를 제한해 포그라운드 채팅을 보호.
     * - queueCap 은 크게   : 새 세션 버스트를 큐가 흡수하고 천천히 소진(백그라운드라 지연 무방).
     */
    public record TitleGeneration(@Positive int threadCap, @Positive int queueCap) {
    }
}
