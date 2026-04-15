package com.readwith.presentation.controller.example.dto;

import com.readwith.domain.example.dto.ExampleCreateCommand;

public record ExampleCreateRequest(
        String name,
        String description
) {

    public ExampleCreateCommand toCommand() {
        return new ExampleCreateCommand(name, description);
    }
}
