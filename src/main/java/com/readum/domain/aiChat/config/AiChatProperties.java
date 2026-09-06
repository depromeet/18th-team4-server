package com.readum.domain.aiChat.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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
     * 스트리밍 한 턴의 기한·여유와 전달 버퍼 크기. 아래 기한들은 재는 대상과 시작점이 서로 달라 하나로 합치지 않는다.
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
     *       끝나기를 기다리는 최대 시간이다. 이 시간이 지나면 남은 작업은 DB 의 미정산 예약 반환에 맡긴다.
     *       SSE 전달의 종료는 이 대기 조건에 넣지 않는다.</li>
     * </ul>
     *
     * <p>아래 둘은 기한이 아니라 <b>여유</b>다 — 무엇을 끊는 값이 아니라, 요청 기록의 만료 시각
     * ({@code ai_chat_turn_request.expires_at})을 접수 시각으로부터 계산할 때 더하는 상한 몫이다.
     * 만료 시각은 "이 시각까지는 아직 정상 처리 중일 수 있다" 는 선이라, 생성 기한만으로 잡으면
     * 정상적으로 선행 처리·후처리 중인 요청을 미정산 예약 반환이 가로채 환불하게 된다.
     * <ul>
     *   <li>prepareAllowanceSeconds — <b>선행 처리 여유</b>. 요청 행을 넣은 뒤(= 접수) 생성 호출을 시작하기까지
     *       걸릴 수 있는 시간이다. 가장 긴 몫은 입력 moderation 의 HTTP 상한 8초
     *       (연결 3초 + 읽기 5초, {@code OpenAiHttpClientConfig})이고, 그 뒤로 이력 조회·예약·전역 게이트·
     *       USER 저장의 DB·Redis 시간이 더 붙는다. 후보값 10초는 8초에 나머지 몫 2초를 얹은 것이다.</li>
     *   <li>postProcessingAllowanceSeconds — <b>후처리 여유</b>. 생성이 끝난 뒤 저장·정산·요청 종료 트랜잭션이
     *       끝나기까지 걸릴 수 있는 시간이다. DB 연결을 얻는 데 Hikari {@code connection-timeout} 3초가 들 수 있고,
     *       그 뒤 트랜잭션 안에서 같은 요청 행을 잠그는 대기가 {@code innodb_lock_wait_timeout} 5초까지 갈 수 있다.
     *       후보값 10초는 연결 획득 3초 + 잠금 대기 5초에 커밋 몫 2초를 얹은 것이다. 실측(N=100)에서 후처리는
     *       연결 대기를 포함해 0.4~0.6초였다.</li>
     * </ul>
     * 여기 적은 초 단위는 설정값과 코드·기본값에서 읽은 상한을 더한 <b>계산</b>이지 측정값이 아니다.
     * 실제 선행 처리·후처리 소요는 재지 않았다.
     *
     * <p><b>한 턴의 시간 예산은 40초</b>다 — 접수부터 선행 여유 10 + 생성 전체 기한 20 + 후처리 여유 10.
     * 그 안에 끝내거나 어느 구간에서든 명시적으로 실패한다. 구간별 상한(moderation HTTP · Hikari 연결 획득 ·
     * 행 잠금 대기)이 이 예산 안에 드는지는 기동 시 {@code AiChatTimeBudgetValidator} 가 대조한다.
     *
     * <p>deliveryQueueCapacity — 연결 1개당 전달 큐에 쌓아둘 델타 개수의 상한이다. 큐가 차면 완성본 대기 모드로
     * 바꾸고 이후 델타는 큐에 넣지 않는다(생성은 계속 누적한다). 초기값은 예상치다:
     * 답변 1건의 델타 수를 설정에 적힌 출력 상한(readum.guardrail.output.max-response-tokens = 1024) 언저리로 잡고,
     * 그 1/4 정도를 버퍼로 두면 짧은 전달 정체는 삼키고 긴 정체만 완성본 모드로 넘어간다는 계산이다.
     * 다만 그 설정은 현재 요청에 max_tokens 로 걸리지 않아 실제 답변이 더 길 수 있다 — 어림잡기용 기준일 뿐이다.
     * <b>이 상한만으로 프로세스 전체의 메모리가 제한되지는 않는다</b> — 실제 청크 크기(델타 문자열 길이),
     * 연결마다 따로 쌓이는 누적 답변, 동시 요청 수를 함께 재서 조정해야 한다.
     *
     * <p>maxInFlightTurns — 이 프로세스가 <b>동시에 진행할 수 있는 채팅 턴 수의 상한</b>이다. 한 턴은 선행 처리 시작부터
     * 마지막 후처리까지를 말한다({@code AiChatInFlightTurnRegistry} 의 추적 구간과 같다). 상한에 닿으면 새 요청을
     * 503({@code AI_CHAT_CAPACITY_EXCEEDED})으로 거절한다.
     *
     * <p>이것은 처리량 목표가 아니라 <b>마지막 안전장치</b>다. 평소 유입을 조절하는 것은 사용자별 폭주 가드와
     * 전역 게이트인데, 둘 다 Redis 에 기대고 Redis 가 죽으면 검사 없이 통과시킨다(fail-open, docs/record/0006).
     * 그 순간 앱이 받는 만큼 다 받아 자기 자원(힙·연결)을 먼저 소진하는 것을 막는 자리다.
     *
     * <p><b>계산값이며 임시값이다.</b> 힙(배포 {@code -Xmx256m})·스트리밍 연결 풀·전역 게이트 세 자원의 상한을 각각
     * 계산해 그 최솟값에 여유를 뺀 값이며, 실측으로 정한 값이 아니다. 산출 과정과 입력값의 출처는
     * {@code docs/architecture/capacity-baseline.md} 의 "채팅 진행 중 턴 상한" 절에 있다.
     * 지표 {@code ai_chat_in_flight_turns} 의 최고치와 {@code ai_chat_in_flight_rejections_total} 을 보고 조정한다.
     */
    public record Streaming(
            @Positive int generationTotalTimeoutSeconds,
            @Positive int generationIdleTimeoutSeconds,
            @Positive int deliveryTimeoutSeconds,
            @Positive int shutdownWaitSeconds,
            @Positive int prepareAllowanceSeconds,
            @Positive int postProcessingAllowanceSeconds,
            @Positive int deliveryQueueCapacity,
            @Positive int maxInFlightTurns
    ) {

        /**
         * 스트리밍 전용 연결 풀이 대기시킬 요청 수 — 진행 중 턴 상한의 1/4.
         * 초과를 45초씩 대기로 숨기지 않고 우리 상한의 503 이 먼저 거절하게 하려는 값이라 작게 둔다.
         * 상한과 한 곳에서 파생시켜 둘이 어긋나지 않게 한다.
         */
        public int streamingPendingAcquireMaxCount() {
            return Math.max(1, maxInFlightTurns / 4);
        }

        /**
         * 요청 기록의 만료 유예 — 접수 시각에 이만큼을 더한 값이 {@code expires_at} 이 된다.
         * 한 턴이 정상적으로 끝나기까지 걸릴 수 있는 시간을 구간별 상한의 합으로 잡는다:
         * <b>선행 처리 여유 + 생성 전체 기한 + 후처리 여유</b>.
         *
         * <p>종료 대기 상한(shutdownWaitSeconds)은 여기 들어가지 않는다 — 그 값은 배포 때
         * <b>얼마나 기다려 줄지</b>를 정하는 값이지 한 턴이 <b>얼마나 걸리는지</b>가 아니다.
         * 후처리에 드는 시간은 postProcessingAllowanceSeconds 가 따로 재고 있다.
         */
        public Duration turnRequestExpiryTimeout() {
            return Duration.ofSeconds(
                    (long) prepareAllowanceSeconds + generationTotalTimeoutSeconds + postProcessingAllowanceSeconds);
        }
    }

}
