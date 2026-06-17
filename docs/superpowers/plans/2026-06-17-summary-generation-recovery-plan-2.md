# 감상문 워커 outbound 페이싱 + quota circuit breaker (Plan 2) Implementation Plan

> **For agentic workers:** TDD, 작업당 커밋. 리뷰는 Codex(`readum-review`)가 담당하므로 서브에이전트 2단계 리뷰는 생략한다.

**Goal:** Plan 1 의 감상문 워커가 OpenAI 를 호출하기 전에 (1) RPM/TPM 토큰 양동이로 선제적 페이싱을 통과하고, (2) quota 소진 시 전역 circuit breaker 로 호출을 멈추게 한다. 더불어 Plan 1 에서 "전부 재시도"로 단순화했던 실패 분류를 정교화한다.

**설계 출처:** `docs/superpowers/specs/2026-06-16-summary-generation-recovery-design.md` §11(circuit breaker)·§12(outbound pacing).

**Tech:** bucket4j(이미 의존성, `AiChatRateLimiter` 가 인바운드에 사용 중), Spring `@ConfigurationProperties`.

---

## 확인된 기존 API (구현 시 그대로 활용)

- `TooManyRequestsException(ErrorCode, RateLimitInfo)` — `getErrorCode()`(`AiChatErrorCode`), `getRateLimitInfo()`.
- `OpenAiResponseErrorHandler` 가 이미 분류해 던진다:
  - 429 + `insufficient_quota` → `TooManyRequestsException(AI_QUOTA_EXHAUSTED, info)`
  - 429 기타 → `TooManyRequestsException(AI_RATE_LIMIT_BURST, info)`
  - 429 외 4xx → `org.springframework.ai.retry.NonTransientAiException` (재시도 무의미)
  - 5xx → `org.springframework.ai.retry.TransientAiException` (재시도 가능)
- `RateLimitInfo.retryAfter()` → `Duration`(nullable). burst 재시도 시각에 사용.
- bucket4j 패턴: `Bandwidth.builder().capacity(n).refillIntervally(n, Duration.ofMinutes(1))` + `Bucket.builder().addLimit(...)`. blocking 대기는 `BlockingBucket.asBlocking().consume(tokens)`.

> `AiSummaryClient.generate(...)` 는 위 예외들을 그대로 던진다(어댑터가 RestClient 에 `OpenAiResponseErrorHandler` 를 물려둠). 워커는 타입으로 분기한다.

---

## 파일 구조

신규(main):
- `domain/aiChat/out/AiCallCircuitBreaker.java` — Port (`isOpen()`, `openFor(Duration)`)
- `infrastructure/ai/openai/circuitbreaker/InMemoryAiCallCircuitBreaker.java` — Adapter
- `domain/summary/out/SummaryCallRateLimiter.java` — Port (`acquire(int estimatedTokens)`, blocking)
- `infrastructure/ai/openai/ratelimit/SummaryCallRateLimiterImpl.java` — bucket4j Adapter (RPM + TPM)
- `domain/summary/service/SummaryTokenEstimator.java` — 입력 토큰 추정(글자수 근사) + 예약 출력
- (설정) `infrastructure/ai/openai/ratelimit/SummaryRateLimitProperties.java`, breaker 지속시간은 여기 또는 `SummaryJobProperties` 에 추가

수정(main):
- `domain/summary/service/SummaryGenerationWorker.java` — breaker 확인 + limiter.acquire + 예외 분류 정교화
- `application.yml` + `src/test/resources/application.yml` — rpm/tpm/reserved/breaker 값

신규(test): 각 단위 테스트 + 워커 분기 테스트

---

## Task P2-1: 전역 circuit breaker (Port + in-memory)

**Files:** `domain/aiChat/out/AiCallCircuitBreaker.java`(Port), `infrastructure/ai/openai/circuitbreaker/InMemoryAiCallCircuitBreaker.java`, test.

- [ ] Port:
```java
package com.readum.domain.aiChat.out;

import java.time.Duration;

/** OpenAI 호출 전역 차단 스위치. quota 소진 등 계정 전역 문제일 때 일정 시간 호출을 멈춘다. */
public interface AiCallCircuitBreaker {
    boolean isOpen();
    void openFor(Duration duration);
}
```
- [ ] Adapter (in-memory, self-healing — 영속 안 함):
```java
package com.readum.infrastructure.ai.openai.circuitbreaker;

import com.readum.domain.aiChat.out.AiCallCircuitBreaker;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class InMemoryAiCallCircuitBreaker implements AiCallCircuitBreaker {

    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.EPOCH);

    @Override
    public boolean isOpen() {
        return Instant.now().isBefore(blockedUntil.get());
    }

    @Override
    public void openFor(Duration duration) {
        blockedUntil.set(Instant.now().plus(duration));
    }
}
```
- [ ] 단위 테스트: 초기 닫힘 / openFor 후 열림 / 시간 경과 후 자동 닫힘(짧은 Duration 으로). `Instant.now()` 직접 의존이라 시간 경과는 `Duration.ofMillis(...)` + 짧은 대기 또는 음수 Duration 으로 검증.

> 다중 인스턴스 시 Redis TTL/DB 어댑터로 교체 (Port 유지). 지금은 in-memory.

---

## Task P2-2: 토큰 추정기

**Files:** `domain/summary/service/SummaryTokenEstimator.java`, test.

요약 프롬프트(=세션 전체 대화)의 입력 토큰을 글자수로 근사하고 예약 출력 토큰을 더한다. (정밀 tokenizer 는 확장점.)

```java
package com.readum.domain.summary.service;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 요약 호출의 예상 토큰(입력 추정 + 예약 출력)을 계산한다. TPM 양동이 차감량으로 쓴다.
 * 입력은 글자수 근사(영어 ~4자/토큰, 한국어는 더 조밀 → 보수적으로 작은 나눗셈 계수 사용).
 * 정밀 tokenizer(jtokkit) 도입은 확장점.
 */
@Component
public class SummaryTokenEstimator {

    private static final double CHARS_PER_TOKEN = 2.5; // 한국어 혼용 보수적 근사

    public int estimate(List<AiChatMessage> messages, int reservedOutputTokens) {
        int chars = messages.stream()
                .mapToInt(message -> message.getContent() == null ? 0 : message.getContent().length())
                .sum();
        int inputTokens = (int) Math.ceil(chars / CHARS_PER_TOKEN);
        return inputTokens + reservedOutputTokens;
    }
}
```
- [ ] 단위 테스트: 빈 목록 → 예약 출력만 / 메시지 글자수 비례 증가.

---

## Task P2-3: outbound RPM/TPM rate limiter (Port + bucket4j)

**Files:** `domain/summary/out/SummaryCallRateLimiter.java`(Port), `infrastructure/ai/openai/ratelimit/SummaryCallRateLimiterImpl.java`, `SummaryRateLimitProperties.java`, test.

- [ ] Port:
```java
package com.readum.domain.summary.out;

/** 감상문 배치의 OpenAI 호출 속도를 RPM/TPM 한도 아래로 묶는 선제적 페이서. */
public interface SummaryCallRateLimiter {
    /** 요청 1개 + estimatedTokens 만큼 확보될 때까지 블로킹 대기 후 통과. */
    void acquire(int estimatedTokens) throws InterruptedException;
}
```
- [ ] Properties (배치 몫 = OpenAI 천장 × 몫 × 안전여유; 운영값):
```java
@Validated
@ConfigurationProperties(prefix = "summary.rate-limit")
public record SummaryRateLimitProperties(
        @Positive int requestsPerMinute,
        @Positive long tokensPerMinute,
        @Positive int reservedOutputTokens
) {}
```
- [ ] Adapter — RPM 버킷 + TPM 버킷, 둘 다 blocking consume:
```java
@Component
public class SummaryCallRateLimiterImpl implements SummaryCallRateLimiter {
    private final Bucket requestBucket;  // capacity=RPM, refill RPM/1min
    private final Bucket tokenBucket;    // capacity=TPM, refill TPM/1min
    // 생성자에서 Properties 로 두 Bucket 구성
    public void acquire(int estimatedTokens) throws InterruptedException {
        requestBucket.asBlocking().consume(1);
        tokenBucket.asBlocking().consume(Math.max(1, Math.min(estimatedTokens, /*capacity*/ tpmCapacity)));
    }
}
```
주의: 한 요청의 estimatedTokens 가 TPM capacity 보다 크면 영원히 대기 → `min(estimatedTokens, capacity)` 로 클램프(로그 경고). 단일 인스턴스 가정(전역 공유 불필요); 다중 인스턴스 시 분산 bucket 확장점.
- [ ] 테스트: 첫 요청 즉시 통과 / 한도 소진 시 대기(작은 capacity + 짧은 refill 로 검증, 또는 consume 횟수 기반).

---

## Task P2-4: 워커 배선 + 실패 분류 정교화

**Files:** `domain/summary/service/SummaryGenerationWorker.java`(수정), test 갱신.

`processOne()` 시작에 breaker 확인 → 열려 있으면 **선점하지 않고** false 반환(작업을 잡지 않음). 생성 직전 `rateLimiter.acquire(estimate)`. 예외 분류:

```java
public boolean processOne() {
    if (circuitBreaker.isOpen()) {
        return false; // 차단 중 — 이번 틱은 아무 작업도 잡지 않음
    }
    String owner = UUID.randomUUID().toString();
    Long jobId = lifecycleService.claimOne(owner);
    if (jobId == null) return false;
    runJob(jobId, owner);
    return true;
}

private void runJob(Long jobId, String owner) {
    SummaryGenerationContext context = lifecycleService.prepareGeneration(jobId, owner);
    if (context == null) return;
    SummaryDraftResult result;
    try {
        int estimate = tokenEstimator.estimate(context.messages(), rateLimitProperties.reservedOutputTokens());
        rateLimiter.acquire(estimate);
        result = aiSummaryClient.generate(context.messages());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        lifecycleService.recordFailure(jobId, owner, true, "INTERRUPTED", e.getMessage(), null);
        return;
    } catch (TooManyRequestsException e) {
        boolean quota = e.getErrorCode() == AiChatErrorCode.AI_QUOTA_EXHAUSTED;
        if (quota) {
            circuitBreaker.openFor(breakerOpenDuration); // 전역 차단
        }
        LocalDateTime retryAt = retryAtFrom(e.getRateLimitInfo()); // Retry-After 있으면 그 시각
        lifecycleService.recordFailure(jobId, owner, true, e.getErrorCode().name(), e.getMessage(), retryAt);
        return;
    } catch (NonTransientAiException e) {           // 4xx — 재시도 무의미
        lifecycleService.recordFailure(jobId, owner, false, "AI_PROVIDER_ERROR", e.getMessage(), null);
        return;
    } catch (TransientAiException e) {               // 5xx — 재시도 가능
        lifecycleService.recordFailure(jobId, owner, true, "AI_PROVIDER_TRANSIENT", e.getMessage(), null);
        return;
    } catch (Exception e) {                          // 알 수 없는 오류 — 보수적으로 재시도
        lifecycleService.recordFailure(jobId, owner, true, "AI_PROVIDER_TRANSIENT", e.getMessage(), null);
        return;
    }
    lifecycleService.recordSuccess(jobId, owner, context.userBookId(), result);
}
```
- 의존성 추가: `AiCallCircuitBreaker`, `SummaryCallRateLimiter`, `SummaryTokenEstimator`, `SummaryRateLimitProperties`(reservedOutputTokens), breaker 지속시간(설정).
- `retryAtFrom(info)`: `info != null && info.retryAfter() != null` 이면 `now + retryAfter`, 아니면 null(서비스가 지수 백오프 사용).
- 테스트 갱신: breaker 열림 → claim/generate 안 함 / quota 예외 → breaker.openFor 호출 + 재시도 기록 / burst → Retry-After 반영 / NonTransient → 재시도불가(FAILED) / Transient → 재시도. (mock: circuitBreaker, rateLimiter, tokenEstimator, lifecycleService, aiSummaryClient)

---

## Task P2-5: 설정값 + yml + 통합 점검

- [ ] `application.yml` + `src/test/resources/application.yml` 에:
```yaml
summary:
  rate-limit:
    requests-per-minute: 60      # 배치 몫(예시; OpenAI 천장×몫×여유로 산정)
    tokens-per-minute: 40000
    reserved-output-tokens: 1024
summary-job:
  breaker-open-seconds: 600      # quota 감지 시 10분 전역 차단
```
- [ ] `SummaryJobProperties` 에 `breakerOpenSeconds`(+`breakerOpen()` Duration) 추가, 또는 breaker 전용 properties.
- [ ] `./gradlew test` 전체 통과(컨텍스트 로딩 = properties 바인딩 확인). 기존 flaky `AiChatMessageSendServiceTest` 는 재실행으로 구분.

---

## Self-review 체크
- breaker 열림 시 **선점 자체를 안 함**(claim 후 방치 X) — 작업이 PROCESSING 에 갇히지 않음.
- estimatedTokens 가 TPM capacity 초과 시 클램프(무한 대기 방지).
- quota=전역 차단, burst=Retry-After 재시도, 4xx=즉시 FAILED, 5xx/기타=재시도 — Plan 1 의 "전부 재시도"를 대체.
- Port/Adapter 분리(교체 여지) — breaker·limiter 둘 다.
- 범위 밖: 대화 절삭(토큰 감축), 다중 인스턴스 분산 bucket/breaker, 정밀 tokenizer.
