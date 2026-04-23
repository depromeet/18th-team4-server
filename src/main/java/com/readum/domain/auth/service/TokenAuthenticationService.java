package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.AuthenticatedPrincipal;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.auth.jwt.JwtTokenProvider;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenAuthenticationService {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistStore tokenBlacklistStore;

    public AuthenticatedPrincipal authenticate(String accessToken) {
        ParsedToken parsed = jwtTokenProvider.parse(accessToken);
        if (parsed.type() != TokenType.ACCESS) {
            throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
        }
        if (tokenBlacklistStore.contains(parsed.jwtId())) {
            throw new UnauthorizedException(AuthErrorCode.TOKEN_REVOKED);
        }
        return new AuthenticatedPrincipal(parsed.userId(), parsed.role());
    }
}
