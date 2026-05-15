package com.readum.model.user.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 테스트에서 특정 id·상태의 {@link User} 를 만들기 위한 조립기.
 * 운영 엔티티에 있던 {@code User.of(...)} 를 대체한다.
 */
@TestOnly
public final class UserFixture {

    private UserFixture() {
    }

    public static User of(
            Long id,
            String deviceId,
            String sessionId,
            Long lastSelectedUserBookId,
            boolean onboardingCompleted,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "deviceId", deviceId);
        ReflectionTestUtils.setField(user, "sessionId", sessionId);
        ReflectionTestUtils.setField(user, "lastSelectedUserBookId", lastSelectedUserBookId);
        ReflectionTestUtils.setField(user, "onboardingCompleted", onboardingCompleted);
        ReflectionTestUtils.setField(user, "createdAt", createdAt);
        ReflectionTestUtils.setField(user, "updatedAt", updatedAt);
        return user;
    }
}
