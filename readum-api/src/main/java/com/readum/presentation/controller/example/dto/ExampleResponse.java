package com.readum.presentation.controller.example.dto;

import com.readum.domain.example.dto.ExampleCreateResult;
import com.readum.domain.example.dto.ExampleResult;

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
