package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.AuthenticatedPrincipal;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenAuthenticationService {

    private final JwtTokenClient jwtTokenClient;
    private final TokenBlacklistStore tokenBlacklistStore;

    public AuthenticatedPrincipal authenticate(String accessToken) {
        ParsedToken parsed = jwtTokenClient.parse(accessToken);
        if (parsed.type() != TokenType.ACCESS) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }
        if (tokenBlacklistStore.contains(parsed.jwtId())) {
            throw new UnauthorizedException(ErrorCode.TOKEN_REVOKED);
        }
        return new AuthenticatedPrincipal(parsed.userId(), parsed.role());
    }
}
