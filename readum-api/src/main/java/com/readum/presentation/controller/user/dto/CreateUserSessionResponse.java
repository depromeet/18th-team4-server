package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.dto.CreateUserSessionResult;

import java.time.LocalDateTime;

public record CreateUserSessionResponse(User user) {

    public record User(Long id, LocalDateTime createdAt) {}

    public static CreateUserSessionResponse from(CreateUserSessionResult result) {
        return new CreateUserSessionResponse(new User(result.userId(), result.createdAt()));
    }
}
