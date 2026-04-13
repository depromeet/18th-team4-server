package com.readwith.domain.example.dto;

import com.readwith.model.example.entity.ExampleEntity;

import java.time.LocalDateTime;

public record ExampleResult(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt
) {

    public static ExampleResult from(ExampleEntity entity) {
        return new ExampleResult(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt()
        );
    }
}
