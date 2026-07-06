package com.readum.infrastructure.redis;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.out.ChatTokenBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatTokenBudgetRedisAdapterTest {

    private static final Long USER_ID = 7L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ChatTokenBudgetRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        AiChatProperties properties = new AiChatProperties(
                new AiChatProperties.Context(8000),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(4, 20000, 512),
                new AiChatProperties.TitleGeneration(4, 2000)
        );
        adapter = new ChatTokenBudgetRedisAdapter(stringRedisTemplate, properties);
    }

    @Test
    void 한도_이내면_Granted_로_예약한다() {
        given(valueOperations.increment(anyString(), anyLong())).willReturn(3000L);

        ChatTokenBudget.Result result = adapter.reserve(USER_ID, 3000);

        assertThat(result).isInstanceOf(ChatTokenBudget.Result.Granted.class);
        assertThat(((ChatTokenBudget.Result.Granted) result).reservedTokens()).isEqualTo(3000);
    }

    @Test
    void 창의_첫_기록이면_TTL_을_건다() {
        given(valueOperations.increment(anyString(), anyLong())).willReturn(3000L); // == 예약량 → 첫 기록

        adapter.reserve(USER_ID, 3000);

        verify(stringRedisTemplate).expire(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 한도를_넘기면_예약을_되돌리고_Denied_와_RetryAfter_를_반환한다() {
        given(valueOperations.increment(anyString(), anyLong())).willReturn(20001L);

        ChatTokenBudget.Result result = adapter.reserve(USER_ID, 3000);

        assertThat(result).isInstanceOf(ChatTokenBudget.Result.Denied.class);
        assertThat(((ChatTokenBudget.Result.Denied) result).retryAfter()).isPositive();
        verify(valueOperations).decrement(anyString(), org.mockito.ArgumentMatchers.eq(3000L));
    }

    @Test
    void Redis_장애면_Bypassed_로_허용한다() {
        given(valueOperations.increment(anyString(), anyLong())).willThrow(new QueryTimeoutException("timeout"));

        ChatTokenBudget.Result result = adapter.reserve(USER_ID, 3000);

        assertThat(result).isInstanceOf(ChatTokenBudget.Result.Bypassed.class);
    }

    @Test
    void settle_은_실측과_예약의_차이만큼_증감한다() {
        adapter.settle(USER_ID, new ChatTokenBudget.Result.Granted(1_751_745_600L, 3000), 2400);

        verify(valueOperations).increment("ai-chat:token-budget:7:1751745600", -600L);
    }

    @Test
    void settle_차이가_0_이면_Redis_를_건드리지_않는다() {
        adapter.settle(USER_ID, new ChatTokenBudget.Result.Granted(1_751_745_600L, 3000), 3000);

        verify(valueOperations, org.mockito.Mockito.never()).increment(anyString(), anyLong());
    }
}
