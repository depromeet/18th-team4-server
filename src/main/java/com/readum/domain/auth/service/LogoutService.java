package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.LogoutCommand;
import com.readum.domain.auth.dto.LogoutResult;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.jwt.JwtTokenProvider;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.model.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistStore tokenBlacklistStore;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public LogoutResult execute(LogoutCommand command) {
        Optional<ParsedToken> refreshToken = parseIfValid(command.refreshToken(), TokenType.REFRESH);
        if (refreshToken.isEmpty()) {
            log.info("Logout 멱등 처리 - Refresh Token 없음 또는 유효하지 않음");
            return LogoutResult.anonymous();
        }

        Long userId = refreshToken.get().userId();
        revokeAllRefreshTokens(userId);
        parseIfValid(command.accessToken(), TokenType.ACCESS)
                .filter(accessToken -> userId.equals(accessToken.userId()))
                .ifPresent(this::blacklist);

        log.info("Logout 완료 userId={}", userId);
        return LogoutResult.of(userId);
    }

    private void revokeAllRefreshTokens(Long userId) {
        int affected = refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
        log.info("Refresh Token 전체 폐기 userId={} affected={}", userId, affected);
    }

    private Optional<ParsedToken> parseIfValid(String token, TokenType expected) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            ParsedToken parsed = jwtTokenProvider.parse(token);
            return parsed.type() == expected ? Optional.of(parsed) : Optional.empty();
        } catch (UnauthorizedException ex) {
            return Optional.empty();
        }
    }

    private void blacklist(ParsedToken accessToken) {
        Duration remaining = Duration.between(Instant.now(), accessToken.expiresAt());
        if (remaining.isZero() || remaining.isNegative()) {
            return;
        }
        try {
            tokenBlacklistStore.add(accessToken.jwtId(), remaining);
        } catch (RuntimeException ex) {
            log.error("Access Token 블랙리스트 등록 실패 - best-effort 처리 jwtId={}", accessToken.jwtId(), ex);
        }
    }
}
