package com.readum.presentation.controller.auth.dto;

import com.readum.domain.auth.dto.TokenPair;

public record TokenRefreshResponse(
        String accessToken,
        long accessTokenExpiresInSeconds
) {

    public static TokenRefreshResponse from(TokenPair pair) {
        return new TokenRefreshResponse(pair.accessToken(), pair.accessTokenTtl().toSeconds());
    }
}
