package com.readum.model.book.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "book")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true, length = 255)
    private String externalId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "authors", length = 500)
    private String authors;

    @Column(name = "publisher", length = 255)
    private String publisher;

    @Column(name = "published_year")
    private Integer publishedYear;

    @Column(name = "cover_url", length = 1000)
    private String coverUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static Book create(
            String externalId,
            String title,
            String authors,
            String publisher,
            Integer publishedYear,
            String coverUrl
    ) {
        return new Book(null, externalId, title, authors, publisher, publishedYear, coverUrl, LocalDateTime.now());
    }
}
