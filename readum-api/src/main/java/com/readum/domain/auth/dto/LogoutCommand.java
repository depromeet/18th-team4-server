package com.readum.domain.auth.dto;

public record LogoutCommand(
        String refreshToken,
        String accessToken
) {
}
