package com.readum.infrastructure.ai.openai.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 전역 게이트 — 프로젝트 × 모델별 토큰 버킷에서 "요청 1 + 추정 토큰"을 확보한다.
 *
 * <p><b>역할은 거르기가 아니라 속도 맞추기다.</b> OpenAI 의 한도는 분 정각에 리셋되는 창이 아니라 연속으로 보충되는
 * 버킷이라, 고정 분 창은 과부하에서 분 초반에 한꺼번에 통과시키고 OpenAI 는 그것을 429 로 돌려보낸다.
 * 토큰 버킷은 보충 속도(한도 ÷ 60 초)대로 고르게 내보내므로 OpenAI 보충 속도와 모양이 같아진다.
 * 자리가 없으면 거절 대신 "기다릴 시간"을 돌려주고, 그 시간을 어떻게 쓸지(짧으면 기다리기·길면 429·반납)는
 * 호출자 쪽 정책({@link OpenAiRateLimitGuard})이 정한다.
 *
 * <p>기능 간 몫은 여기서 배분하지 않는다 — OpenAI 프로젝트 분리({@link OpenAiProject})가 격리를 맡고,
 * 버킷은 프로젝트마다 따로 돈다. 계정 quota 소진(조직 전체 문제)은 어느 프로젝트 버킷으로도 못 막으므로,
 * 감지 지점이 {@link #enterQuotaCooldown} 으로 쿨다운을 열면 그 동안 모든 경로를 거절한다.
 *
 * <p>계상 대상은 실제로 쓰이는 전부다 — 시스템 프롬프트·재전송 이력·요약 등 오버헤드 포함(사용자 예산과 다른 점).
 * 생성이 실패하면 호출 지점이 확보 때 받은 {@link GateReservation} 을 {@link #compensate} 에 돌려줘 버킷에 되돌린다.
 * Redis 장애·미설정 모델은 허용 + ERROR (fail-open, 조용한 통과 방지용 로그 필수).
 */
@Slf4j
@Component
public class OpenAiRequestGate {

    private static final String KEY_PREFIX = "ai:global:";
    private static final String QUOTA_COOLDOWN_SUFFIX = ":quota-cooldown";
    private static final String REQUEST_BUCKET_SUFFIX = ":bucket:rpm";
    private static final String TOKEN_BUCKET_SUFFIX = ":bucket:tpm";
    private static final String BUCKET_TOKENS_FIELD = "tokens";
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = loadScript();

    private final StringRedisTemplate stringRedisTemplate;
    private final OpenAiProjectProperties properties;
    private final Duration bucketKeyTtl;

    public OpenAiRequestGate(StringRedisTemplate stringRedisTemplate, OpenAiProjectProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        // 버킷이 비어 있어도 burstSeconds 면 가득 찬다. 그 두 배가 지나도록 아무도 안 두드렸으면 키를 지운다 —
        // 키가 없으면 스크립트가 가득 찬 버킷으로 본다.
        // 이 값은 최소 TTL 이다 — 버킷이 빚(음수)을 졌으면 스크립트가 다 갚고 가득 찰 때까지로 TTL 을 늘린다.
        this.bucketKeyTtl = Duration.ofSeconds(properties.gate().burstSeconds() * 2L);
    }

    public enum RejectReason { RATE_BUDGET, QUOTA_COOLDOWN }

    /** 계상 내역 — 보상({@link #compensate})이 같은 버킷에 되돌리는 데 필요한 전부. */
    public record GateReservation(OpenAiProject project, String model, int estimatedTokens) {
    }

    public sealed interface Decision {
        /** 버킷에서 뺐고 통과 — 생성 실패 시 reservation 으로 되돌릴 수 있다. */
        record Permitted(GateReservation reservation) implements Decision {
        }

        /** 계상 없이 통과(fail-open: 한도 미설정·Redis 장애·스크립트 응답 없음) — 되돌릴 계상이 없다. */
        record PermittedUncounted() implements Decision {
        }

        /**
         * 지금은 자리가 없다. {@code retryAfter} 는 RATE_BUDGET 이면 버킷에 자리가 나기까지의 시간,
         * QUOTA_COOLDOWN 이면 쿨다운이 풀리기까지의 시간이다.
         */
        record Rejected(Duration retryAfter, RejectReason reason) implements Decision {
        }
    }

    public Decision tryAcquire(OpenAiProject project, String model, int estimatedTokens) {
        OpenAiProjectProperties.ModelLimit limit = properties.limitOf(project, model);
        if (limit == null) {
            log.error("전역 게이트에 한도 미설정 — 검사 없이 허용 project={} model={}", project.key(), model);
            return new Decision.PermittedUncounted();
        }
        try {
            Duration cooldown = remainingQuotaCooldown(model);
            if (cooldown != null) {
                // 계정 quota 소진 쿨다운 중 — 버킷을 건드리지 않고 즉시 거절(감지 지점이 이미 열어 둠).
                return new Decision.Rejected(cooldown, RejectReason.QUOTA_COOLDOWN);
            }
            int burstSeconds = properties.gate().burstSeconds();
            double requestRatePerMillis = limit.requestsPerMinute() / 60_000.0;
            double tokenRatePerMillis = limit.tokensPerMinute() / 60_000.0;
            long requestCapacity = Math.max(1L, Math.round(requestRatePerMillis * 1_000L * burstSeconds));
            long tokenCapacity = Math.max(1L, Math.round(tokenRatePerMillis * 1_000L * burstSeconds));
            Long waitMillis = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(requestBucketKey(project, model), tokenBucketKey(project, model)),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(requestCapacity),
                    String.valueOf(requestRatePerMillis),
                    String.valueOf(tokenCapacity),
                    String.valueOf(tokenRatePerMillis),
                    String.valueOf(estimatedTokens),
                    String.valueOf(bucketKeyTtl.toMillis())
            );
            if (waitMillis == null) {
                log.error("전역 게이트 토큰 버킷 스크립트 응답 없음 — 검사 없이 허용 project={} model={}",
                        project.key(), model);
                return new Decision.PermittedUncounted();
            }
            if (waitMillis > 0) {
                return new Decision.Rejected(Duration.ofMillis(waitMillis), RejectReason.RATE_BUDGET);
            }
            return new Decision.Permitted(new GateReservation(project, model, estimatedTokens));
        } catch (DataAccessException e) {
            log.error("전역 게이트 Redis 접근 실패 — 검사 없이 허용 project={} model={}", project.key(), model, e);
            return new Decision.PermittedUncounted();
        }
    }

    /**
     * 생성 실패 시 보상 — 확보 때 뺀 "요청 1 + 추정 토큰"을 같은 버킷에 되돌린다.
     * 버킷 크기를 넘는 몫은 다음 확보의 보충 계산이 크기에서 잘라 내므로 여기서 상한을 보지 않는다.
     * 키가 이미 만료됐으면 tokens 필드만 있는 키가 생기는데, 스크립트는 시각 필드가 없는 키를
     * 가득 찬 버킷으로 보므로 무해하다 — TTL 만 다시 걸어 잔존을 막는다.
     * Redis 장애는 ERROR 로그 후 무시한다 — 보상 실패가 응답 경로를 막으면 안 되고, 보충이 안전망이다.
     */
    public void compensate(GateReservation reservation) {
        try {
            String requestKey = requestBucketKey(reservation.project(), reservation.model());
            String tokenKey = tokenBucketKey(reservation.project(), reservation.model());
            stringRedisTemplate.opsForHash().increment(requestKey, BUCKET_TOKENS_FIELD, 1.0d);
            stringRedisTemplate.expire(requestKey, bucketKeyTtl);
            stringRedisTemplate.opsForHash().increment(tokenKey, BUCKET_TOKENS_FIELD, (double) reservation.estimatedTokens());
            stringRedisTemplate.expire(tokenKey, bucketKeyTtl);
        } catch (DataAccessException e) {
            log.error("전역 게이트 보상 실패 — 버킷 보충에 맡김 project={} model={}",
                    reservation.project().key(), reservation.model(), e);
        }
    }

    /** 계정 quota 소진을 감지한 지점이 호출 — 이 시간 동안 전 경로를 막는다. */
    public void enterQuotaCooldown(String model, Duration cooldown) {
        try {
            stringRedisTemplate.opsForValue().set(quotaCooldownKey(model), "1", cooldown);
            log.error("전역 게이트 quota 쿨다운 진입 model={} cooldown={}s", model, cooldown.toSeconds());
        } catch (DataAccessException e) {
            log.error("전역 게이트 quota 쿨다운 기록 실패 model={}", model, e);
        }
    }

    /** 워커 사전 차단용 — 쿨다운 중이면 job 을 선점하지 않게 한다. Redis 장애 시 false(fail-open). */
    public boolean isInQuotaCooldown(String model) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaCooldownKey(model)));
        } catch (DataAccessException e) {
            log.error("전역 게이트 quota 쿨다운 조회 실패 — 통과 처리 model={}", model, e);
            return false;
        }
    }

    private Duration remainingQuotaCooldown(String model) {
        Long ttlSeconds = stringRedisTemplate.getExpire(quotaCooldownKey(model), TimeUnit.SECONDS);
        return (ttlSeconds != null && ttlSeconds > 0) ? Duration.ofSeconds(ttlSeconds) : null;
    }

    private String quotaCooldownKey(String model) {
        return KEY_PREFIX + model + QUOTA_COOLDOWN_SUFFIX;
    }

    private String requestBucketKey(OpenAiProject project, String model) {
        return KEY_PREFIX + project.key() + ":" + model + REQUEST_BUCKET_SUFFIX;
    }

    private String tokenBucketKey(OpenAiProject project, String model) {
        return KEY_PREFIX + project.key() + ":" + model + TOKEN_BUCKET_SUFFIX;
    }

    /**
     * 스크립트 본문을 초기화 시점에 즉시 읽는다 — 지연 평가면 파일 누락이 기동은 통과하고 첫 요청에서
     * ScriptingException(DataAccessException 아님)으로 터져 fail-open 을 비켜간다. 폭주 가드와 같은 이유.
     */
    private static DefaultRedisScript<Long> loadScript() {
        ClassPathResource scriptResource = new ClassPathResource("redis/openai-token-bucket.lua");
        String scriptText;
        try (InputStream scriptStream = scriptResource.getInputStream()) {
            scriptText = new String(scriptStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "전역 게이트 토큰 버킷 Lua 스크립트 로드 실패: " + scriptResource.getPath(), e);
        }
        return new DefaultRedisScript<>(scriptText, Long.class);
    }
}
