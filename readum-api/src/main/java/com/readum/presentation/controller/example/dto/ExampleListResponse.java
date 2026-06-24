package com.readum.presentation.controller.example.dto;

import com.readum.domain.example.dto.ExampleResult;

import java.util.List;

public record ExampleListResponse(List<ExampleResponse> examples) {

    public static ExampleListResponse from(List<ExampleResult> results) {
        return new ExampleListResponse(
                results.stream().map(ExampleResponse::from).toList()
        );
    }
}
