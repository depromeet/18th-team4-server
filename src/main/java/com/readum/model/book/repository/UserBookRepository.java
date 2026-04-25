package com.readum.model.book.repository;

import com.readum.model.book.entity.UserBook;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBookRepository extends JpaRepository<UserBook, Long> {

    boolean existsByUserId(Long userId);
}
