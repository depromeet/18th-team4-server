package com.readum.model.book.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 테스트에서 특정 id 의 {@link Book} 을 만들기 위한 조립기.
 * 운영 엔티티에 있던 {@code Book.of(...)} 를 대체한다.
 */
@TestOnly
public final class BookFixture {

    private BookFixture() {
    }

    public static Book of(
            Long id,
            String externalId,
            String title,
            String authors,
            String publisher,
            Integer publishedYear,
            String coverUrl,
            LocalDateTime createdAt
    ) {
        Book book = new Book();
        ReflectionTestUtils.setField(book, "id", id);
        ReflectionTestUtils.setField(book, "externalId", externalId);
        ReflectionTestUtils.setField(book, "title", title);
        ReflectionTestUtils.setField(book, "authors", authors);
        ReflectionTestUtils.setField(book, "publisher", publisher);
        ReflectionTestUtils.setField(book, "publishedYear", publishedYear);
        ReflectionTestUtils.setField(book, "coverUrl", coverUrl);
        ReflectionTestUtils.setField(book, "createdAt", createdAt);
        return book;
    }
}
