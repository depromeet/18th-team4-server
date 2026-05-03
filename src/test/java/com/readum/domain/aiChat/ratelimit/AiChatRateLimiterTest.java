package com.readum.domain.aiChat.ratelimit;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiChatRateLimiterTest {

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private AiChatProperties aiChatProperties;

    @InjectMocks
    private AiChatRateLimiter aiChatRateLimiter;

    @BeforeEach
    void setUp() {
        given(aiChatProperties.rateLimit())
                .willReturn(new AiChatProperties.RateLimit(10, 5));
    }

    @Test
    void 한도_미만이면_예외없이_통과한다() {
        Long userId = 1L;
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(userId), any(LocalDateTime.class)))
                .willReturn(4L);

        assertThatCode(() -> aiChatRateLimiter.check(userId))
                .doesNotThrowAnyException();
    }

    @Test
    void 한도_도달이면_TooManyRequestsException_을_던진다() {
        Long userId = 1L;
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(userId), any(LocalDateTime.class)))
                .willReturn(5L);

        assertThatThrownBy(() -> aiChatRateLimiter.check(userId))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_RATE_LIMIT_BURST);
    }

    @Test
    void 한도_초과면_RateLimitInfo_에_retryAfter_와_limit_정보가_담긴다() {
        Long userId = 1L;
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(userId), any(LocalDateTime.class)))
                .willReturn(7L);

        assertThatThrownBy(() -> aiChatRateLimiter.check(userId))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .satisfies(ex -> {
                    RateLimitInfo info = ex.getRateLimitInfo();
                    assertThat(info).isNotNull();
                    assertThat(info.retryAfter()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(info.limitRequests()).isEqualTo(5L);
                    assertThat(info.remainingRequests()).isZero();
                });
    }

    @Test
    void 카운트_쿼리는_window_초만큼_과거_시점부터_조회한다() {
        Long userId = 1L;
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(userId), any(LocalDateTime.class)))
                .willReturn(0L);

        LocalDateTime before = LocalDateTime.now().minusSeconds(10);
        aiChatRateLimiter.check(userId);
        LocalDateTime after = LocalDateTime.now().minusSeconds(10);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(aiChatMessageRepository)
                .countRecentUserMessagesByOwner(eq(userId), captor.capture());
        LocalDateTime since = captor.getValue();
        assertThat(since).isBetween(before, after);
    }
}
