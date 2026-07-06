package com.readum.domain.userBook.dto;

import com.readum.model.book.entity.Book;
import com.readum.model.userBook.entity.UserBook;

import java.time.LocalDateTime;

public record UserBookCreateResult(
        Long id,
        Long userId,
        String bookExternalId,
        String title,
        String authors,
        String publisher,
        Integer publishedYear,
        String coverUrl,
        LocalDateTime createdAt
) {

    public static UserBookCreateResult from(UserBook userBook, Book book) {
        return new UserBookCreateResult(
                userBook.getId(),
                userBook.getUserId(),
                book.getExternalId(),
                book.getTitle(),
                book.getAuthors(),
                book.getPublisher(),
                book.getPublishedYear(),
                book.getCoverUrl(),
                userBook.getCreatedAt()
        );
    }
}
