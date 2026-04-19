package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.LogoutCommand;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {

    private final JwtTokenClient jwtTokenClient;
    private final TokenBlacklistStore tokenBlacklistStore;
    private final RefreshTokenStore refreshTokenStore;

    public void execute(LogoutCommand command) {
        ParsedToken parsed = jwtTokenClient.parse(command.accessToken());
        if (parsed.type() != TokenType.ACCESS) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        Duration remaining = Duration.between(Instant.now(), parsed.expiresAt());
        tokenBlacklistStore.add(parsed.jwtId(), remaining);
        refreshTokenStore.deleteAll(parsed.userId());

        log.info("Logout 완료 userId={} jwtId={}", parsed.userId(), parsed.jwtId());
    }
}
