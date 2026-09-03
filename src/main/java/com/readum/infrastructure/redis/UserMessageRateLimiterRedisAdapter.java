package com.readum.infrastructure.redis;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.out.UserMessageRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Redis ZSET 슬라이딩 윈도우 기반 메시지 폭주 가드. 키 = ai-chat:rate-limit:{userId},
 * 멤버 = 요청별 유일값, score = 요청 시각(epoch millis). Lua 스크립트 한 번으로
 * "창 밖 제거 → 건수 검사 → 이번 요청 기록" 을 원자로 수행해 검사·기록 사이 경쟁이 없다.
 * 키는 PEXPIRE 로 자동 만료되어 정리 작업이 없다.
 * Redis 장애 시 허용 + ERROR 로그 (fail-open) — 폭주 가드 목적상 차단이 더 큰 피해라서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMessageRateLimiterRedisAdapter implements UserMessageRateLimiter {

    private static final String KEY_PREFIX = "ai-chat:rate-limit:";
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = loadScript();

    private final StringRedisTemplate stringRedisTemplate;
    private final AiChatProperties aiChatProperties;

    @Override
    public Result tryConsume(Long userId) {
        AiChatProperties.RateLimit rateLimit = aiChatProperties.rateLimit();
        long nowMillis = System.currentTimeMillis();
        long windowMillis = rateLimit.countPeriodSeconds() * 1000L;
        // member 는 요청마다 유일해야 한다 — 같은 ms 의 동시 요청이 같은 member 로 ZADD 되면
        // score 만 덮어써져 한 건으로 합쳐지고, 그만큼 폭주가 덜 세인다.
        String member = UUID.randomUUID().toString();
        try {
            Long scriptResult = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(KEY_PREFIX + userId),
                    String.valueOf(nowMillis),
                    String.valueOf(windowMillis),
                    String.valueOf(rateLimit.maxMessageCount()),
                    member
            );
            if (scriptResult == null) {
                log.error("메시지 rate limit Lua 스크립트 응답 없음 — 검사 없이 허용 (fail-open) userId={}", userId);
                return new Result.Bypassed();
            }
            return scriptResult == 1L ? new Result.Allowed() : new Result.Denied();
        } catch (DataAccessException e) {
            log.error("메시지 rate limit Redis 접근 실패 — 검사 없이 허용 (fail-open) userId={}", userId, e);
            return new Result.Bypassed();
        }
    }

    private static DefaultRedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("redis/user-message-rate-limit.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
