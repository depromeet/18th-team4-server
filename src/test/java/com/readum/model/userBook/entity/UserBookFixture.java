package com.readum.model.userBook.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link UserBook} 를 만드는 명명 팩토리.
 * "저장되어 id 가 부여된" 상태와 "특정 등록 시각을 가진" 상태를 이름으로 드러낸다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
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
        return new UserBook(id, userId, bookId, createdAt);
    }

    /**
     * 저장되어 id 가 부여된 사용자-도서 등록. 등록 시각은 단언과 무관해 내부 기본값(now).
     */
    public static UserBook persistedUserBook(Long id, Long userId, Long bookId) {
        return new UserBook(id, userId, bookId, LocalDateTime.now());
    }

    /**
     * 특정 시각에 등록된, 저장 전(id 미부여) 사용자-도서.
     * createdAt DESC 정렬 같은 등록 시각 의존 조회 검증용 (저장 시 id 가 부여됨).
     */
    public static UserBook userBookRegisteredAt(Long userId, Long bookId, LocalDateTime createdAt) {
        return new UserBook(null, userId, bookId, createdAt);
    }
}
