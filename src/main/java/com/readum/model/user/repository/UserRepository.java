package com.readum.model.user.repository;

import com.readum.model.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findBySessionId(String sessionId);

    /**
     * 등록 도서(UserBook) 삭제 cascade 용 — 삭제되는 도서를 "마지막 선택 도서" 로 가리키던
     * 사용자들의 참조를 null 로 정리한다 (사라진 userBookId 를 가리키는 dangling 참조 방지).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update User user
               set user.lastSelectedUserBookId = null
             where user.lastSelectedUserBookId = :userBookId
            """)
    int clearLastSelectedUserBook(@Param("userBookId") Long userBookId);
}
