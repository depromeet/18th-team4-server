package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.dto.TokenRefreshCommand;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshService {

    private final JwtTokenClient jwtTokenClient;
    private final RefreshTokenStore refreshTokenStore;

    public TokenPair execute(TokenRefreshCommand command) {
        ParsedToken parsed = jwtTokenClient.parse(command.refreshToken());
        if (parsed.type() != TokenType.REFRESH) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        Long userId = parsed.userId();
        Optional<String> stored = refreshTokenStore.findCurrent(userId);
        if (stored.isEmpty()) {
            log.warn("Refresh Token 저장소에 값이 없음 userId={}", userId);
            throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        if (!stored.get().equals(command.refreshToken())) {
            if (refreshTokenStore.existsInGrace(userId, parsed.jwtId())) {
                log.info("유예 기간 내 구형 Refresh Token 수락 userId={} jwtId={}", userId, parsed.jwtId());
            } else {
                log.error("Refresh Token 재사용 감지 - 해당 userId 전체 토큰 폐기 userId={} jwtId={}", userId, parsed.jwtId());
                refreshTokenStore.deleteAll(userId);
                throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
            }
        }

        String newAccessJwtId = UUID.randomUUID().toString();
        String newRefreshJwtId = UUID.randomUUID().toString();
        String newAccessToken = jwtTokenClient.generateAccessToken(userId, parsed.role(), newAccessJwtId);
        String newRefreshToken = jwtTokenClient.generateRefreshToken(userId, parsed.role(), newRefreshJwtId);

        refreshTokenStore.rotate(
                userId,
                parsed.jwtId(),
                newRefreshToken,
                jwtTokenClient.refreshTokenTtl(),
                jwtTokenClient.refreshGracePeriod()
        );

        log.info("Refresh Token 갱신 완료 userId={} oldJwtId={} newJwtId={}", userId, parsed.jwtId(), newRefreshJwtId);
        return new TokenPair(
                newAccessToken,
                newRefreshToken,
                jwtTokenClient.accessTokenTtl(),
                jwtTokenClient.refreshTokenTtl()
        );
    }
}
