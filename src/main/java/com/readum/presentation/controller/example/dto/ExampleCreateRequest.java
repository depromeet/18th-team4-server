package com.readum.presentation.controller.example.dto;

import com.readum.domain.example.dto.ExampleCreateCommand;

public record ExampleCreateRequest(
        String name,
        String description
) {

    public ExampleCreateCommand toCommand() {
        return new ExampleCreateCommand(name, description);
    }
}
