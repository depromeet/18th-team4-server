package com.readum.domain.user.dto;

import com.readum.model.user.entity.User;

import java.time.LocalDateTime;
import java.util.UUID;

public record CreateUserSessionResult(
        Long userId,
        UUID sessionId,
        LocalDateTime createdAt
) {

    public static CreateUserSessionResult from(User user, UUID sessionId) {
        return new CreateUserSessionResult(user.getId(), sessionId, user.getCreatedAt());
    }
}
