package com.readum.infrastructure.aiChat.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 자동 적재가 마지막으로 <b>성공한</b> 스캔 시각. 건너뛴 회차의 대상을 다음 회차가 이어 담게 하는 표식이다.
 *
 * <p>왜 필요한가: 적재 대상은 "최근 24시간 안에 대화가 있은 세션" 이라 회차를 한 번 건너뛰면 그 24시간은
 * 다음 회차의 조회 범위 밖으로 빠진다 — 공급자 장애로 하루를 걸렀을 때 그날 대화한 사람들의 감상문이
 * 영영 자동 적재되지 않는다는 뜻이다.
 *
 * <p><b>기준점은 첫 시도에서, 가용 확인보다 먼저 심는다.</b> 나중에 심으면 첫 회차가 장애로 거절될 때
 * 표식이 없는 채로 끝나고, 다음 회차가 다시 "최근 24시간" 으로 좁혀 그 사이 구간을 통째로 잃는다.
 *
 * <p><b>읽지 못하면 스캔을 하지 않는다.</b> 조용히 기본 24시간으로 되돌리면 건너뛴 구간을 잃고도
 * 정상처럼 보인다 — 이 표식은 "무엇을 이미 훑었는가" 의 정본이므로, 모르는 채로 훑는 것보다 다음 회차를 기다리는 편이 낫다.
 *
 * <p><b>제한:</b> 표식은 Redis 에 {@value #RETENTION_DAYS}일 보관한다. 자동 적재가 그보다 오래 멈춰 있었다면
 * 표식이 만료돼 기준점이 다시 "최근 24시간" 으로 잡히고, 그 사이 구간은 자동 적재되지 않는다(수동 요청은 가능).
 */
@Slf4j
@Component
public class SummaryScanWatermark {

    private static final String KEY = "ai:summary:last-scan-at";

    /** 표식 보관 기간(일). 되돌아보는 범위의 사실상 상한이기도 하다. */
    static final int RETENTION_DAYS = 30;

    private static final Duration RETENTION = Duration.ofDays(RETENTION_DAYS);
    private static final DefaultRedisScript<String> WATERMARK_SCRIPT = loadScript();

    private static final String OPERATION_INIT = "init";
    private static final String OPERATION_ADVANCE = "advance";

    private final StringRedisTemplate stringRedisTemplate;
    private final ZoneId zone;

    /**
     * 시간대를 바꿔 끼우는 생성자가 따로 있으므로, 스프링이 쓸 생성자를 명시한다 —
     * 후보가 둘이면 어느 쪽도 고르지 못하고 기본 생성자를 찾다 기동이 깨진다.
     */
    @Autowired
    public SummaryScanWatermark(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, ZoneId.systemDefault());
    }

    SummaryScanWatermark(StringRedisTemplate stringRedisTemplate, ZoneId zone) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.zone = zone;
    }

    /**
     * 표식이 없으면 {@code baseline} 으로 심고, 지금의 표식 값을 돌려준다.
     * 심는 것과 읽는 것이 한 번에 끝나므로, 여러 서버가 같은 순간에 처음 돌아도 기준점이 하나로 정해진다.
     *
     * @throws IllegalStateException 표식을 읽지도 심지도 못했을 때 — 호출자는 이번 회차를 건너뛴다
     */
    public LocalDateTime initializeOrRead(LocalDateTime baseline) {
        return execute(OPERATION_INIT, baseline);
    }

    /**
     * 성공한 스캔 시각으로 표식을 전진시킨다. 이미 더 앞선 값이 있으면 그대로 둔다 —
     * 여러 서버가 겹쳐 돌 때 늦게 끝난 쪽이 앞선 표식을 되돌리면 같은 구간을 다시 훑는다.
     *
     * @throws IllegalStateException 전진시키지 못했을 때
     */
    public void advanceTo(LocalDateTime scannedAt) {
        execute(OPERATION_ADVANCE, scannedAt);
    }

    private LocalDateTime execute(String operation, LocalDateTime value) {
        String reply;
        try {
            reply = stringRedisTemplate.execute(
                    WATERMARK_SCRIPT,
                    List.of(KEY),
                    operation,
                    String.valueOf(toEpochMillis(value)),
                    String.valueOf(RETENTION.toMillis()));
        } catch (RuntimeException redisFailure) {
            throw new IllegalStateException("감상문 자동 적재 표식을 다루지 못했습니다: " + operation, redisFailure);
        }
        if (reply == null) {
            throw new IllegalStateException("감상문 자동 적재 표식 응답이 없습니다: " + operation);
        }
        return fromEpochMillis(Long.parseLong(reply));
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(zone).toInstant().toEpochMilli();
    }

    private LocalDateTime fromEpochMillis(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
    }

    private static DefaultRedisScript<String> loadScript() {
        ClassPathResource scriptResource = new ClassPathResource("redis/summary-scan-watermark.lua");
        String scriptText;
        try (InputStream scriptStream = scriptResource.getInputStream()) {
            scriptText = new String(scriptStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "감상문 자동 적재 표식 Lua 스크립트 로드 실패: " + scriptResource.getPath(), e);
        }
        return new DefaultRedisScript<>(scriptText, String.class);
    }
}
