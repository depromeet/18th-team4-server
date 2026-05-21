package com.readum.model.user.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link User} 를 만드는 명명 팩토리.
 * 운영 DB 에 존재할 수 있는 상태만, 이름으로 의도를 드러내며 노출한다.
 * 운영 엔티티의 invariant 를 우회하지 않도록 엔티티 생성자는 PRIVATE 으로 두고,
 * 픽스처는 같은 패키지에서 접근 가능한 protected 무인자 생성자로 객체를 만든 뒤
 * {@link ReflectionTestUtils} 로 필드를 채운다 (테스트 전용).
 */
@TestOnly
public final class UserFixture {

    private UserFixture() {
    }

    /**
     * 저장되어 id 가 부여된, 온보딩 미완료의 평범한 사용자.
     * deviceId/lastSelectedUserBookId 는 없는 상태.
     */
    public static User persistedUser(Long id, String sessionId) {
        return persistedUser(id, sessionId, null, false);
    }

    /**
     * 저장되어 id 가 부여된 사용자. 마지막 선택 도서와 온보딩 완료 여부를 지정한다.
     */
    public static User persistedUser(
            Long id,
            String sessionId,
            Long lastSelectedUserBookId,
            boolean onboardingCompleted
    ) {
        return assemble(id, null, sessionId, lastSelectedUserBookId, onboardingCompleted);
    }

    /**
     * 모든 필드를 받아 {@link ReflectionTestUtils} 로 직접 채우는 조립 헬퍼.
     * 명명 팩토리만 외부에 노출하고, 필드 주입 메커니즘은 여기 한 곳에 묶는다.
     */
    private static User assemble(
            Long id,
            String deviceId,
            String sessionId,
            Long lastSelectedUserBookId,
            boolean onboardingCompleted
    ) {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "deviceId", deviceId);
        ReflectionTestUtils.setField(user, "sessionId", sessionId);
        ReflectionTestUtils.setField(user, "lastSelectedUserBookId", lastSelectedUserBookId);
        ReflectionTestUtils.setField(user, "onboardingCompleted", onboardingCompleted);
        ReflectionTestUtils.setField(user, "createdAt", now);
        ReflectionTestUtils.setField(user, "updatedAt", now);
        return user;
    }
}
