package com.readum.model.user.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link UserBook} 를 만드는 명명 팩토리.
 * "저장되어 id 가 부여된" 상태와 "특정 등록 시각을 가진" 상태를 이름으로 드러낸다.
 * 운영 엔티티의 invariant 를 우회하지 않도록 엔티티 생성자는 PRIVATE 으로 두고,
 * 픽스처는 같은 패키지에서 접근 가능한 protected 무인자 생성자로 객체를 만든 뒤
 * {@link ReflectionTestUtils} 로 필드를 채운다 (테스트 전용).
 */
@TestOnly
public final class UserBookFixture {

    private UserBookFixture() {
    }

    /**
     * 저장되어 id 가 부여된 사용자-도서 등록. 등록 시각(createdAt)이 단언에 쓰일 때 사용.
     */
    public static UserBook persistedUserBook(
            Long id, Long userId, Long bookId, LocalDateTime createdAt
    ) {
        return assemble(id, userId, bookId, createdAt);
    }

    /**
     * 저장되어 id 가 부여된 사용자-도서 등록. 등록 시각은 단언과 무관해 내부 기본값(now).
     */
    public static UserBook persistedUserBook(Long id, Long userId, Long bookId) {
        return assemble(id, userId, bookId, LocalDateTime.now());
    }

    /**
     * 특정 시각에 등록된, 저장 전(id 미부여) 사용자-도서.
     * createdAt DESC 정렬 같은 등록 시각 의존 조회 검증용 (저장 시 id 가 부여됨).
     */
    public static UserBook userBookRegisteredAt(Long userId, Long bookId, LocalDateTime createdAt) {
        return assemble(null, userId, bookId, createdAt);
    }

    private static UserBook assemble(Long id, Long userId, Long bookId, LocalDateTime createdAt) {
        UserBook userBook = new UserBook();
        ReflectionTestUtils.setField(userBook, "id", id);
        ReflectionTestUtils.setField(userBook, "userId", userId);
        ReflectionTestUtils.setField(userBook, "bookId", bookId);
        ReflectionTestUtils.setField(userBook, "createdAt", createdAt);
        return userBook;
    }
}
