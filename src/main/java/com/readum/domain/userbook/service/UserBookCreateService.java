package com.readum.domain.userbook.service;

import com.readum.domain.exception.ConflictException;
import com.readum.domain.userbook.dto.UserBookCreateCommand;
import com.readum.domain.userbook.dto.UserBookCreateResult;
import com.readum.domain.userbook.exception.UserBookErrorCode;
import com.readum.model.book.entity.Book;
import com.readum.model.book.entity.UserBook;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.book.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserBookCreateService {

    private final BookRepository bookRepository;
    private final UserBookRepository userBookRepository;

    @Transactional
    public UserBookCreateResult execute(UserBookCreateCommand command) {
        // 1. Book 마스터 UPSERT (동일 external_id 존재 시 기존 행 재사용, 없으면 신규 생성)
        bookRepository.upsert(
                command.bookExternalId(),
                command.title(),
                command.authors(),
                command.publisher(),
                command.publishedYear(),
                command.coverUrl()
        );

        // 2. Book 엔티티 확보
        Book book = bookRepository.findByExternalId(command.bookExternalId())
                .orElseThrow(() -> new IllegalStateException(
                        "upsert 후 book을 찾을 수 없음: " + command.bookExternalId()));

        // 3. (userId, bookId) 중복 체크
        Optional<UserBook> existing = userBookRepository.findByUserIdAndBookId(command.userId(), book.getId());
        if (existing.isPresent()) {
            UserBookCreateResult conflictResult = toResult(existing.get(), book);
            throw new ConflictException(UserBookErrorCode.ALREADY_EXISTS, conflictResult);
        }

        // 4. 신규 UserBook 등록
        UserBook saved = userBookRepository.save(UserBook.create(command.userId(), book.getId()));
        return toResult(saved, book);
    }

    private UserBookCreateResult toResult(UserBook userBook, Book book) {
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
