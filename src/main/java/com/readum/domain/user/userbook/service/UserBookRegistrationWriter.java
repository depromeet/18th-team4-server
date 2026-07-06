package com.readum.domain.user.userbook.service;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.user.userbook.dto.UserBookCreateCommand;
import com.readum.domain.user.userbook.dto.UserBookCreateResult;
import com.readum.domain.user.userbook.exception.UserBookErrorCode;
import com.readum.model.book.entity.Book;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도서 등록의 DB 쓰기 구간만 트랜잭션으로 묶는 헬퍼.
 * 알라딘 HTTP 호출이 트랜잭션(= DB 커넥션 점유) 안에 들어오지 않도록
 * UserBookCreateService 에서 분리했다.
 */
@Component
@RequiredArgsConstructor
class UserBookRegistrationWriter {

    private final BookRepository bookRepository;
    private final UserBookRepository userBookRepository;
    private final UserBookConflictReader userBookConflictReader;

    @Transactional
    public UserBookCreateResult register(UserBookCreateCommand command, BookResult bookInfo) {
        // 1. Book 마스터 UPSERT (동일 external_id 존재 시 기존 행 재사용, 없으면 신규 생성)
        bookRepository.upsert(
                command.bookExternalId(),
                bookInfo.title(),
                bookInfo.author(),
                bookInfo.publisher(),
                bookInfo.publishedYear(),
                bookInfo.coverUrl()
        );

        // 2. Book 엔티티 확보
        Book book = bookRepository.findByExternalId(command.bookExternalId())
                .orElseThrow(() -> new IllegalStateException(
                        "upsert 후 book을 찾을 수 없음: " + command.bookExternalId()));

        // 3. 신규 UserBook 등록
        // 중복(일반 재등록 또는 race condition) 발생 시 DataIntegrityViolationException을 잡아
        // 새 트랜잭션으로 재조회 후 409로 전환한다.
        try {
            UserBook saved = userBookRepository.save(UserBook.create(command.userId(), book.getId()));
            return UserBookCreateResult.from(saved, book);
        } catch (DataIntegrityViolationException ex) {
            UserBook raced = userBookConflictReader.find(command.userId(), book.getId())
                    .orElseThrow(() -> ex);
            throw new ConflictException(UserBookErrorCode.ALREADY_EXISTS, UserBookCreateResult.from(raced, book));
        }
    }
}
