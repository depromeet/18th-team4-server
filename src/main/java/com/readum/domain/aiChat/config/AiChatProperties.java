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
        @Valid Streaming streaming
) {

    /**
     * 채팅 컨텍스트 조립·요약 설정. 조립 모델: [시스템+책][누적 요약][요약 반영 지점 이후 최근 원문 대화][현재 메시지].
     * - assemblyRecentRawMaxTokens: 조립기가 한 호출에 실어보낼 최근 원문 대화의 최대 토큰(안전핀). 요약이 밀렸을 때 초과분은 오래된 턴부터 제외.
     * - keepRecentRawTokens: 워커가 요약하지 않고 항상 원문으로 남겨둘 최근 원문 대화 크기(품질 장치). 요약 범위 계산의 하한.
     * - summarizeTriggerTokenThreshold: 요약 반영 지점 이후 최근 원문 대화 토큰 합이 이를 넘으면 요약 job 을 적재한다.
     * - summaryEstimatedOutputTokens: 요약 갱신 호출의 출력 추정값 — 게이트 예약 계상용(프롬프트 길이 지시가 아님).
     *
     * 불변식(계약): {@code assemblyRecentRawMaxTokens >= keepRecentRawTokens} 여야 한다. 조립기가 실어보낼 최대가
     * 워커가 남겨둔 최근 원문보다 작으면, 워커가 남긴 원문을 조립기가 다 싣지 못해 오래된 턴이 조용히 누락된다.
     */
    public record Context(
            @Positive int assemblyRecentRawMaxTokens,
            @Positive int keepRecentRawTokens,
            @Positive int summarizeTriggerTokenThreshold,
            @Positive int summaryEstimatedOutputTokens
    ) {
    }

    public record MessageRule(@Positive int maxContentLength) {
    }

    /**
     * 사용자별 폭주 가드 — 창 내 메시지 전송 시도 수. 10초에 5건 이상은 정상 사용이 아닌 것으로 본다.
     * 저장은 Redis ZSET 슬라이딩 윈도우 (UserMessageRateLimiter Port).
     * 비용 방어의 본체는 TokenBudget(토큰 예산)이고, 이 가드는 초 단위 폭주(무한 retry, 키 유출)만 막는다.
     */
    public record RateLimit(
            @Positive int countPeriodSeconds,
            @Positive int maxMessageCount
    ) {
    }

    /**
     * 사용자별 토큰 예산 — KST 달력 하루(자정 리셋)당 dailyTokens 씩.
     * 사용자가 보낸 메시지 입력 + 받은 응답 출력만 계상한다(시스템 프롬프트·재전송 이력·요약 등
     * 서비스 오버헤드는 미계상 — 공정성 한도). 선불 예약(메시지 추정 + estimatedOutputTokens) 후
     * 출력을 실측으로 보정한다. 저장은 DB 원장 user_token_budget (UserTokenBudgetWriter).
     * estimatedOutputTokens 는 회계용 출력 추정값이다 — 프롬프트 길이 지시·수신 제한이 아니다.
     */
    public record TokenBudget(
            @Positive int dailyTokens,
            @Positive int estimatedOutputTokens
    ) {
    }

    /**
     * 스트리밍 한 턴의 기한과 전달 버퍼 크기. 아래 네 기한은 재는 대상과 시작점이 서로 달라 하나로 합치지 않는다.
     * 값은 모두 <b>후보값</b>이며 부하 측정 뒤 조정한다.
     *
     * <p>기한별 시작점(무엇을 언제부터 재는가):
     * <ul>
     *   <li>generationTotalTimeoutSeconds — <b>생성 전체 기한</b>. 외부 API(OpenAI) 호출을 시작한 시점부터 잰다.
     *       청크가 계속 도착하더라도 적용한다. 사용자를 무한정 기다리게 하지 않기 위한 절대 상한.</li>
     *   <li>generationIdleTimeoutSeconds — <b>무응답 기한</b>. 스트림이 시작된 시점, 그 뒤로는 마지막으로 인정한
     *       수신 시점부터 다음 데이터를 기다리는 시간이다. 무엇을 수신으로 인정할지는 생성 구독 쪽(Task 7)에서 정한다.</li>
     *   <li>deliveryTimeoutSeconds — <b>전달 기한</b>. <b>전달 채널을 만든 시점</b>(선행 처리 통과 직후,
     *       SseEmitter 를 돌려주는 시점)부터 잰다. 전달 VT 가 실제로 실행을 시작한 시점으로 재면 VT 실행이 밀릴수록
     *       기한이 뒤로 밀려 상한 구실을 못 하므로, 시작점을 채널 생성 시점으로 고정한다.
     *       기한이 지나면 <b>새 SSE 쓰기를 시작하지 않는다</b>는 뜻이며, 이미 진행 중인 쓰기를 회수한다는 보장은 아니다.
     *       완성본 교체(replace)까지 담아야 하므로 생성 전체 기한보다 길게 둔다.</li>
     *   <li>shutdownWaitSeconds — <b>종료 대기 상한</b>. shutdown 신호를 받은 시점부터 진행 중인 생성·후처리가
     *       끝나기를 기다리는 최대 시간이다. 이 시간이 지나면 남은 작업은 DB 의 미완료 예약 복구에 맡긴다.
     *       SSE 전달의 종료는 이 대기 조건에 넣지 않는다.</li>
     * </ul>
     *
     * <p>deliveryQueueCapacity — 연결 1개당 전달 큐에 쌓아둘 델타 개수의 상한이다. 큐가 차면 완성본 대기 모드로
     * 바꾸고 이후 델타는 큐에 넣지 않는다(생성은 계속 누적한다). 초기값은 예상치다:
     * 답변 1건의 델타 수를 설정에 적힌 출력 상한(readum.guardrail.output.max-response-tokens = 1024) 언저리로 잡고,
     * 그 1/4 정도를 버퍼로 두면 짧은 전달 정체는 삼키고 긴 정체만 완성본 모드로 넘어간다는 계산이다.
     * 다만 그 설정은 현재 요청에 max_tokens 로 걸리지 않아 실제 답변이 더 길 수 있다 — 어림잡기용 기준일 뿐이다.
     * <b>이 상한만으로 프로세스 전체의 메모리가 제한되지는 않는다</b> — 실제 청크 크기(델타 문자열 길이),
     * 연결마다 따로 쌓이는 누적 답변, 동시 요청 수를 함께 재서 조정해야 한다.
     */
    public record Streaming(
            @Positive int generationTotalTimeoutSeconds,
            @Positive int generationIdleTimeoutSeconds,
            @Positive int deliveryTimeoutSeconds,
            @Positive int shutdownWaitSeconds,
            @Positive int deliveryQueueCapacity
    ) {
    }

}
