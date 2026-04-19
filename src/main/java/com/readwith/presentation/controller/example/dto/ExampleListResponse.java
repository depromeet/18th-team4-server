package com.readwith.presentation.controller.example.dto;

import com.readwith.domain.example.dto.ExampleResult;

import java.util.List;

public record ExampleListResponse(List<ExampleResponse> examples) {

    public static ExampleListResponse from(List<ExampleResult> results) {
        return new ExampleListResponse(
                results.stream().map(ExampleResponse::from).toList()
        );
    }
}
