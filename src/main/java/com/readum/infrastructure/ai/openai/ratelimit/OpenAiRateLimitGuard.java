package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * OpenAI 호출 전에 전역 게이트 통과를 확보한다 — 막히면 {@link TooManyRequestsException} 으로 번역해 던진다.
 * 게이트({@link OpenAiRequestGate})는 판정만 하고, 거절을 도메인 공용 예외로 번역하는 정책은 이 한 곳이 소유한다.
 *
 * <p><b>기다릴지는 프로젝트가 정한다.</b> 게이트가 "자리가 나기까지 t" 를 돌려주면,
 * {@link OpenAiProject#CHAT} 은 t 가 설정된 상한 안이면 그만큼 기다렸다 다시 확보한다 — 사용자에게는 첫 토큰이
 * 조금 늦는 것으로만 보인다. 나머지 프로젝트(감상문·컨텍스트 요약·제목)는 기다리지 않고 바로 던진다 —
 * 워커는 반납하고 다음 주기에 다시 선점하는 편이, 잠든 채 작업 소유권을 쥐는 것보다 단순하다.
 * 기다렸다 다시 두드리는 사이 다른 호출이 먼저 가져갈 수 있으므로, 상한 안에서 반복한다.
 * 상한은 벽시계 마감으로 잰다 — 잠든 시간뿐 아니라 다시 두드리는 Redis 호출에 걸린 시간도 마감에서 빠지므로,
 * 이 메서드 전체는 "대기 상한 + 마지막 Redis 호출 하나" 안에 끝난다(매달린 Redis 앞에서도 마찬가지다).
 * quota 쿨다운은 분 단위라 어느 프로젝트도 기다리지 않는다.
 * 거절 이후 무엇을 할지(429 응답·재큐·재시도 횟수를 올리지 않고 대기열로 되돌리기·스킵)는 이 예외를 받는 각 호출자가 정한다.
 */
@Component
public class OpenAiRateLimitGuard {

    /** 대기 구현을 바꿔 끼우기 위한 틈 — 테스트가 실제로 잠들지 않게 한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final OpenAiRequestGate gate;
    private final Duration chatMaxWait;
    private final Sleeper sleeper;

    /** 마감을 재는 시계 — 테스트가 시간이 흐른 상황을 실제로 기다리지 않고 만들 수 있게 바꿔 끼운다. */
    private final LongSupplier nanoTime;

    @Autowired
    public OpenAiRateLimitGuard(OpenAiRequestGate gate, OpenAiProjectProperties properties) {
        this(gate, properties, Thread::sleep, System::nanoTime);
    }

    OpenAiRateLimitGuard(
            OpenAiRequestGate gate, OpenAiProjectProperties properties, Sleeper sleeper, LongSupplier nanoTime) {
        this.gate = gate;
        this.chatMaxWait = properties.gate().chatMaxWait();
        this.sleeper = sleeper;
        this.nanoTime = nanoTime;
    }

    /**
     * 버킷에서 "요청 1 + 추정 토큰"을 확보한다. 통과하면 계상 내역을 반환하고, 막히면 던진다.
     * 계상 내역은 생성 실패 시 {@link OpenAiRequestGate#compensate} 로 되돌리기 위한 것이고,
     * fail-open 통과는 계상이 없었으므로 빈 Optional 이다. 보상하지 않는 호출 경로(제목·감상문·요약)는
     * 반환값을 무시하면 된다.
     *
     * @throws TooManyRequestsException 버킷 자리 없음(AI_RATE_LIMIT_BURST, 기다릴 시간이 상한을 넘을 때)
     *                                  또는 계정 quota 쿨다운(AI_QUOTA_EXHAUSTED)
     */
    public Optional<OpenAiRequestGate.GateReservation> acquireOrThrow(
            OpenAiProject project, String model, int estimatedTokens) {
        long deadlineNanos = nanoTime.getAsLong()
                + (project == OpenAiProject.CHAT ? chatMaxWait.toNanos() : 0L);
        while (true) {
            switch (gate.tryAcquire(project, model, estimatedTokens)) {
                case OpenAiRequestGate.Decision.Permitted(OpenAiRequestGate.GateReservation reservation) -> {
                    return Optional.of(reservation);
                }
                case OpenAiRequestGate.Decision.PermittedUncounted() -> {
                    return Optional.empty();
                }
                case OpenAiRequestGate.Decision.Rejected rejected -> {
                    if (!canWait(rejected, remainingWait(deadlineNanos))) {
                        throw toTooManyRequests(rejected);
                    }
                    try {
                        sleeper.sleep(rejected.retryAfter());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw toTooManyRequests(rejected);
                    }
                }
            }
        }
    }

    /**
     * 확보했던 계상을 되돌린다 — {@link OpenAiRequestGate#compensate} 로의 단순 위임이다.
     * 확보와 보상이 같은 협력자를 거치게 해서, 게이트 접근을 이 한 곳이 소유한다는 취지를 유지한다.
     * 보상 여부의 판단(생성이 실제로 토큰을 소모했는지)은 호출자의 몫이다.
     */
    public void compensate(OpenAiRequestGate.GateReservation reservation) {
        gate.compensate(reservation);
    }

    /** 마감까지 남은 시간. 이미 지났으면 0 — 게이트를 두드리는 데 시간을 다 썼다는 뜻이다. */
    private Duration remainingWait(long deadlineNanos) {
        long remainingNanos = deadlineNanos - nanoTime.getAsLong();
        return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    private static boolean canWait(OpenAiRequestGate.Decision.Rejected rejected, Duration remainingWait) {
        // quota 쿨다운은 기다려서 풀리는 종류가 아니다(분 단위). 버킷 대기만 흡수한다.
        return rejected.reason() == OpenAiRequestGate.RejectReason.RATE_BUDGET
                && !rejected.retryAfter().isZero()
                && rejected.retryAfter().compareTo(remainingWait) <= 0;
    }

    private TooManyRequestsException toTooManyRequests(OpenAiRequestGate.Decision.Rejected rejected) {
        AiChatErrorCode code = rejected.reason() == OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN
                ? AiChatErrorCode.AI_QUOTA_EXHAUSTED
                : AiChatErrorCode.AI_RATE_LIMIT_BURST;
        return new TooManyRequestsException(code, RateLimitInfo.retryAfterOnly(rejected.retryAfter()));
    }
}
