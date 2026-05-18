package com.readum.model.user.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link User} 를 만드는 명명 팩토리.
 * 운영 DB 에 존재할 수 있는 상태만, 이름으로 의도를 드러내며 노출한다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
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
        LocalDateTime now = LocalDateTime.now();
        return new User(id, null, sessionId, lastSelectedUserBookId, onboardingCompleted, now, now);
    }
}
