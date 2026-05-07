package com.readum.model.user.repository;

import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.projection.UserBookListItemProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserBookRepository extends JpaRepository<UserBook, Long> {
    Optional<UserBook> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserId(Long userId);

    boolean existsByUserIdAndBookId(Long userId, Long bookId);

    Optional<UserBook> findByUserIdAndBookId(Long userId, Long bookId);

    @Query("""
            select new com.readum.model.user.repository.projection.UserBookListItemProjection(
                       book.id
                     , book.title
                     , book.publisher
                     , book.publishedYear
                     , book.coverUrl
                   )
              from UserBook userBook
              join Book book
                on book.id = userBook.bookId
             where userBook.userId = :userId
             order by userBook.createdAt desc
                    , userBook.id desc
            """)
    List<UserBookListItemProjection> findAllByUserIdOrderByCreatedAtDescIdDesc(@Param("userId") Long userId);
}
