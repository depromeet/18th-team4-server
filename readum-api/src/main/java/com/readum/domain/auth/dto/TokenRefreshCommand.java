package com.readum.domain.auth.dto;

public record TokenRefreshCommand(
        String refreshToken
) {
}
