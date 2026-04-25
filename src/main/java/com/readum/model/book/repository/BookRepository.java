package com.readum.model.book.repository;

import com.readum.model.book.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    Optional<Book> findByExternalId(String externalId);

    /**
     * 동시에 여러 요청이 같은 external_id로 INSERT를 시도해도
     * DataIntegrityViolationException 없이 안전하게 처리한다.
     *
     * - 행이 없으면: INSERT 수행
     * - 행이 이미 있으면: ON DUPLICATE KEY UPDATE 절이 no-op(external_id = external_id)으로 처리되어
     *   기존 행을 그대로 유지하고 예외를 발생시키지 않는다.
     * - AUTO_INCREMENT가 소진되지 않도록 실제 업데이트가 발생하지 않는 no-op 컬럼을 사용한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @NativeQuery(value = """
            INSERT INTO book (external_id, title, authors, publisher, published_year, cover_url, created_at)
            VALUES (:externalId, :title, :authors, :publisher, :publishedYear, :coverUrl, NOW())
            ON DUPLICATE KEY UPDATE external_id = external_id
            """)
    void upsert(
            @Param("externalId") String externalId,
            @Param("title") String title,
            @Param("authors") String authors,
            @Param("publisher") String publisher,
            @Param("publishedYear") Integer publishedYear,
            @Param("coverUrl") String coverUrl
    );
}
