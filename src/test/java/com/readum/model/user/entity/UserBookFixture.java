package com.readum.model.user.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 테스트에서 특정 id 의 {@link UserBook} 를 만들기 위한 조립기.
 * 운영 엔티티에 있던 {@code UserBook.of(...)} 를 대체한다.
 */
@TestOnly
public final class UserBookFixture {

    private UserBookFixture() {
    }

    public static UserBook of(Long id, Long userId, Long bookId, LocalDateTime createdAt) {
        UserBook userBook = new UserBook();
        ReflectionTestUtils.setField(userBook, "id", id);
        ReflectionTestUtils.setField(userBook, "userId", userId);
        ReflectionTestUtils.setField(userBook, "bookId", bookId);
        ReflectionTestUtils.setField(userBook, "createdAt", createdAt);
        return userBook;
    }
}
