package com.readum.domain.userBook.service;

import com.readum.model.userBook.entity.UserBook;
import com.readum.model.userBook.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * DataIntegrityViolationException 발생 후 Hibernate 세션이 무효화된 상태에서도
 * 새 트랜잭션으로 UserBook을 안전하게 재조회하기 위한 헬퍼.
 */
@Component
@RequiredArgsConstructor
class UserBookConflictReader {

    private final UserBookRepository userBookRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UserBook> find(Long userId, Long bookId) {
        return userBookRepository.findByUserIdAndBookId(userId, bookId);
    }
}
