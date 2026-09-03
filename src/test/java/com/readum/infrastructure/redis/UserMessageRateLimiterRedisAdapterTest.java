package com.readum.infrastructure.redis;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.out.UserMessageRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserMessageRateLimiterRedisAdapterTest {

    private static final Long USER_ID = 7L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private UserMessageRateLimiterRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        AiChatProperties properties = new AiChatProperties(
                new AiChatProperties.Context(8000, 2000, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(4, 20000, 512)
        );
        adapter = new UserMessageRateLimiterRedisAdapter(stringRedisTemplate, properties);
    }

    @SuppressWarnings("unchecked")
    private void givenScriptReturns(Long scriptResult) {
        given(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .willReturn(scriptResult);
    }

    @Test
    void 스크립트가_1_을_반환하면_Allowed_다() {
        givenScriptReturns(1L);

        UserMessageRateLimiter.Result tryConsumeResult = adapter.tryConsume(USER_ID);

        assertThat(tryConsumeResult).isInstanceOf(UserMessageRateLimiter.Result.Allowed.class);
    }

    @Test
    void 스크립트가_0_을_반환하면_Denied_다() {
        givenScriptReturns(0L);

        UserMessageRateLimiter.Result tryConsumeResult = adapter.tryConsume(USER_ID);

        assertThat(tryConsumeResult).isInstanceOf(UserMessageRateLimiter.Result.Denied.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 스크립트에_사용자별_키와_현재시각_창길이_한도_유일_member_를_전달한다() {
        givenScriptReturns(1L);
        long beforeMillis = System.currentTimeMillis();

        adapter.tryConsume(USER_ID);

        long afterMillis = System.currentTimeMillis();
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(stringRedisTemplate).execute(
                any(RedisScript.class), keysCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly("ai-chat:rate-limit:7");
        List<Object> scriptArgs = argsCaptor.getAllValues();
        assertThat(Long.parseLong((String) scriptArgs.get(0))).isBetween(beforeMillis, afterMillis);
        assertThat(scriptArgs.get(1)).isEqualTo("10000"); // countPeriodSeconds 10초 → millis
        assertThat(scriptArgs.get(2)).isEqualTo("5");     // maxMessageCount
        assertThat((String) scriptArgs.get(3)).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 같은_사용자의_연속_호출도_서로_다른_member_로_기록한다() {
        givenScriptReturns(1L);

        adapter.tryConsume(USER_ID);
        adapter.tryConsume(USER_ID);

        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(stringRedisTemplate, org.mockito.Mockito.times(2)).execute(
                any(RedisScript.class), anyList(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());

        // 호출당 4개 인자 — member 는 각 호출의 4번째. 같은 ms 라도 ZADD 덮어쓰기가 없어야 한다.
        List<Object> scriptArgs = argsCaptor.getAllValues();
        assertThat(scriptArgs.get(3)).isNotEqualTo(scriptArgs.get(7));
    }

    @Test
    @SuppressWarnings("unchecked")
    void Redis_장애면_Bypassed_로_허용한다() {
        given(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .willThrow(new QueryTimeoutException("timeout"));

        UserMessageRateLimiter.Result tryConsumeResult = adapter.tryConsume(USER_ID);

        assertThat(tryConsumeResult).isInstanceOf(UserMessageRateLimiter.Result.Bypassed.class);
    }

    @Test
    void 스크립트_응답이_없으면_Bypassed_로_허용한다() {
        givenScriptReturns(null);

        UserMessageRateLimiter.Result tryConsumeResult = adapter.tryConsume(USER_ID);

        assertThat(tryConsumeResult).isInstanceOf(UserMessageRateLimiter.Result.Bypassed.class);
    }
}
