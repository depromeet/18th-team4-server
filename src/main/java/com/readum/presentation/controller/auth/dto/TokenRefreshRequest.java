package com.readum.presentation.controller.auth.dto;

import com.readum.domain.auth.dto.TokenRefreshCommand;

public record TokenRefreshRequest(
        String refreshToken
) {

    public TokenRefreshCommand toCommand() {
        return new TokenRefreshCommand(refreshToken);
    }
}
