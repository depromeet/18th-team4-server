package com.readum.model.user.repository.projection;

public record UserBookListItemProjection(
        Long id,
        String title,
        String publisher,
        Integer publishedYear,
        String coverUrl
) {
}
