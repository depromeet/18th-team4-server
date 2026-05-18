package com.readum.model.book.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link Book} 을 만드는 명명 팩토리.
 * "저장되어 id 가 부여된" 도서 상태를 이름으로 드러낸다 — 등록된 도서의 id 가
 * UserBook 생성/단언에 쓰이므로 도메인 팩토리({@code Book.create}) 로 대체할 수 없다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
 * createdAt 은 어떤 호출부에서도 단언하지 않으므로 내부 기본값(now)을 쓴다.
 */
@TestOnly
public final class BookFixture {

    private BookFixture() {
    }

    /**
     * 저장되어 id 가 부여된 도서 마스터.
     */
    public static Book persistedBook(
            Long id,
            String externalId,
            String title,
            String authors,
            String publisher,
            Integer publishedYear,
            String coverUrl
    ) {
        return new Book(
                id, externalId, title, authors, publisher, publishedYear, coverUrl, LocalDateTime.now()
        );
    }
}
