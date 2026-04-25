package com.readum.model.book.repository;

import com.readum.model.book.entity.UserBook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserBookRepository extends JpaRepository<UserBook, Long> {

    Optional<UserBook> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndBookId(Long userId, Long bookId);

    Optional<UserBook> findByUserIdAndBookId(Long userId, Long bookId);
}
