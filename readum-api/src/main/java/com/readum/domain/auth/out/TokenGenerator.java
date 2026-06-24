package com.readum.domain.auth.out;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.RefreshTokenPayload;

public interface TokenGenerator {

    String generateAccessToken(Long userId, String role, String jwtId);

    String generateRefreshToken(RefreshTokenPayload payload);

    ParsedToken parse(String token);
}
