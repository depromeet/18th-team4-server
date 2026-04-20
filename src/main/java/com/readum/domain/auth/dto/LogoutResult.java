package com.readum.domain.auth.dto;

public record LogoutResult(
        Long userId
) {

    public static LogoutResult of(Long userId) {
        return new LogoutResult(userId);
    }

    public static LogoutResult anonymous() {
        return new LogoutResult(null);
    }
}
