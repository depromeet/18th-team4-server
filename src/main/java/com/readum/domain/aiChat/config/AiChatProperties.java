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
        @Valid Context context,
        @Valid MessageRule message,
        @Valid RateLimit rateLimit,
        @Valid TokenBudget tokenBudget,
        @Valid TitleGeneration titleGeneration
) {

    /**
     * 채팅 컨텍스트 조립 설정. rawTailHardCapTokens: 원문 꼬리 토큰 절대 상한 —
     * 이력을 newest-first 로 token_count 합산해 이 값까지 싣고, 초과분은 오래된 턴부터 제외한다.
     * (요약 결합은 PR-3. 지금은 요약 없이 원문 꼬리만.)
     */
    public record Context(@Positive int rawTailHardCapTokens) {
    }

    public record MessageRule(@Positive int maxContentLength) {
    }

    /**
     * 사용자별 폭주 가드 — 상태 무관 USER 메시지 수. 10초에 5건 이상은 정상 사용이 아닌 것으로 본다.
     * 비용 방어의 본체는 TokenBudget(토큰 예산)이고, 이 가드는 초 단위 폭주(무한 retry, 키 유출)만 막는다.
     */
    public record RateLimit(
            @Positive int countPeriodSeconds,
            @Positive int maxMessageCount
    ) {
    }

    /**
     * 사용자별 토큰 예산 — KST 자정 앵커 windowHours 창마다 tokensPerWindow 씩.
     * 사용자가 보낸 메시지 입력 + 받은 응답 출력만 계상한다(시스템 프롬프트·재전송 이력·요약 등
     * 서비스 오버헤드는 미계상 — 공정성 한도). 선불 예약(메시지 추정 + estimatedOutputTokens) 후
     * 출력을 실측으로 보정한다. 저장은 Redis (ChatTokenBudget Port).
     * estimatedOutputTokens 는 회계용 출력 추정값이다 — 프롬프트 길이 지시·수신 제한이 아니다.
     */
    public record TokenBudget(
            @Positive int windowHours,
            @Positive int tokensPerWindow,
            @Positive int estimatedOutputTokens
    ) {
    }

    /**
     * 제목 생성 전용 스케줄러 크기.
     * 제목 생성은 사용자 응답 경로 밖에서 도는 백그라운드 작업이라 latency 가 중요하지 않다.
     * 영속화(Done 이벤트 직전의 짧은 JDBC)가 쓰는 전역 boundedElastic 과 같은 풀을 쓰면,
     * 첫 메시지가 몰릴 때 느린 제목 생성 LLM 호출이 스레드를 다 점유해 영속화가 큐에서 밀린다(격벽 부재).
     * 그래서 전용 풀로 분리한다:
     * - threadCap : 동시 제목 생성 수의 상한(동시에 빌려 쓰는 OpenAI 연결·DB 커넥션 수도 함께 제한). 저빈도라 작게 둬도 충분.
     * - queueCap  : reactor 의 newBoundedElastic 에서 이 값은 backing thread 1개당 큐 한도(per-thread)다.
     *               따라서 전역 backlog 상한 = threadCap × queueCap 이므로, 둘의 곱이 의도한 한도가 되도록 잡는다.
     */
    public record TitleGeneration(@Positive int threadCap, @Positive int queueCap) {
    }
}
