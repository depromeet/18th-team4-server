package com.readwith.presentation.controller.example.dto;

import com.readwith.domain.example.dto.ExampleCreateResult;
import com.readwith.domain.example.dto.ExampleResult;

import java.time.LocalDateTime;

public record ExampleResponse(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt
) {

    public static ExampleResponse from(ExampleCreateResult result) {
        return new ExampleResponse(result.id(), result.name(), null, null);
    }

    public static ExampleResponse from(ExampleResult result) {
        return new ExampleResponse(
                result.id(),
                result.name(),
                result.description(),
                result.createdAt()
        );
    }
}
