package com.readum.domain.aiChat.ratelimit;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * AI 채팅의 사용자별 burst 차단을 담당하는 도메인 헬퍼.
 * Command/Query 서비스 형태에 맞지 않아 service 패키지가 아닌 ratelimit sub-package 에 위치한다.
 *
 * <p>현재 정책은 단일 슬라이딩 윈도우(burstWindowSeconds 안에 burstMaxCount 회) 만 본다.
 * 정밀한 사용자별·tier 별 정책은 트래픽 데이터가 쌓인 후 도입 예정이며,
 * 그 시점에는 본 클래스의 check() 시그니처는 유지하면서 내부 카운터 소스만 Redis 등으로 교체하면 된다.
 *
 * <p>비용 방어선이 목적이므로, 한도 초과 시 retryAfter 는 윈도우 길이를 그대로 돌려 보낸다 (보수적 추정).
 * 정밀하게는 윈도우 내 가장 오래된 메시지의 만료 시점까지지만, 한 쿼리만으로 충분하지 않아 생략.
 */
@Component
@RequiredArgsConstructor
public class AiChatRateLimiter {

    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatProperties aiChatProperties;

    public void check(Long userId) {
        AiChatProperties.RateLimit limit = aiChatProperties.rateLimit();
        LocalDateTime since = LocalDateTime.now().minusSeconds(limit.burstWindowSeconds());

        long recentCount = aiChatMessageRepository.countRecentUserMessagesByOwner(userId, since);
        if (recentCount < limit.burstMaxCount()) {
            return;
        }

        RateLimitInfo info = new RateLimitInfo(
                Duration.ofSeconds(limit.burstWindowSeconds()),
                (long) limit.burstMaxCount(),
                null,
                0L,
                null,
                null,
                null
        );
        throw new TooManyRequestsException(AiChatErrorCode.USER_RATE_LIMIT_BURST, info);
    }
}
