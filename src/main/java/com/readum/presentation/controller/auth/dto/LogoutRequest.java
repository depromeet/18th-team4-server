package com.readum.presentation.controller.auth.dto;

import com.readum.domain.auth.dto.LogoutCommand;

public record LogoutRequest(
        String refreshToken,
        String accessToken
) {

    public LogoutCommand toCommand() {
        return new LogoutCommand(refreshToken, accessToken);
    }
}
