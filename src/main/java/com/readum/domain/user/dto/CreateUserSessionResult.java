package com.readum.domain.user.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CreateUserSessionResult(
        Long userId,
        UUID sessionId,
        LocalDateTime createdAt
) {
}
