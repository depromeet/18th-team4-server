package com.readum.model.book.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link Book} 을 만드는 명명 팩토리.
 * "저장되어 id 가 부여된" 도서 상태를 이름으로 드러낸다 — 등록된 도서의 id 가
 * UserBook 생성/단언에 쓰이므로 도메인 팩토리({@code Book.create}) 로 대체할 수 없다.
 * 운영 엔티티의 invariant 를 우회하지 않도록 엔티티 생성자는 PRIVATE 으로 두고,
 * 픽스처는 같은 패키지에서 접근 가능한 protected 무인자 생성자로 객체를 만든 뒤
 * {@link ReflectionTestUtils} 로 필드를 채운다 (테스트 전용).
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
        return assemble(id, externalId, title, authors, publisher, publishedYear, coverUrl);
    }

    private static Book assemble(
            Long id,
            String externalId,
            String title,
            String authors,
            String publisher,
            Integer publishedYear,
            String coverUrl
    ) {
        Book book = new Book();
        ReflectionTestUtils.setField(book, "id", id);
        ReflectionTestUtils.setField(book, "externalId", externalId);
        ReflectionTestUtils.setField(book, "title", title);
        ReflectionTestUtils.setField(book, "authors", authors);
        ReflectionTestUtils.setField(book, "publisher", publisher);
        ReflectionTestUtils.setField(book, "publishedYear", publishedYear);
        ReflectionTestUtils.setField(book, "coverUrl", coverUrl);
        ReflectionTestUtils.setField(book, "createdAt", LocalDateTime.now());
        return book;
    }
}
