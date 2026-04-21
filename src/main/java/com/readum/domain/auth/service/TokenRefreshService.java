package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.dto.RefreshTokenRotation;
import com.readum.domain.auth.dto.RotateResult;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.dto.TokenRefreshCommand;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
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
            throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
        }

        Long userId = parsed.userId();
        String role = parsed.role();
        String oldJwtId = parsed.jwtId();

        Instant now = Instant.now();
        String newRefreshJwtId = UUID.randomUUID().toString();
        Instant newRefreshExpiresAt = now.plus(jwtTokenClient.refreshTokenTtl());

        RotateResult result = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId,
                oldJwtId,
                newRefreshJwtId,
                now,
                newRefreshExpiresAt,
                jwtTokenClient.refreshGracePeriod()
        ));

        return switch (result.outcome()) {
            case ROTATED -> buildRotatedPair(userId, role, newRefreshJwtId, now, newRefreshExpiresAt, oldJwtId);
            case GRACE_HIT -> buildGraceHitPair(userId, role, result, oldJwtId);
            case NOT_FOUND -> {
                log.warn("Refresh Token 저장소에 값이 없음 userId={} jwtId={}", userId, oldJwtId);
                throw new UnauthorizedException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
            }
            case EXPIRED -> {
                log.warn("만료된 Refresh Token 사용 userId={} jwtId={}", userId, oldJwtId);
                throw new UnauthorizedException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
            }
            case REUSE_DETECTED -> {
                log.error("Refresh Token 재사용 감지 - 해당 userId 전체 토큰 폐기 userId={} jwtId={}", userId, oldJwtId);
                throw new UnauthorizedException(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
            }
        };
    }

    private TokenPair buildRotatedPair(
            Long userId, String role, String newRefreshJwtId,
            Instant newIssuedAt, Instant newExpiresAt, String oldJwtId
    ) {
        String newAccessJwtId = UUID.randomUUID().toString();
        String newAccessToken = jwtTokenClient.generateAccessToken(userId, role, newAccessJwtId);
        String newRefreshToken = jwtTokenClient.generateRefreshToken(new RefreshTokenPayload(
                userId, role, newRefreshJwtId, newIssuedAt, newExpiresAt
        ));

        log.info("Refresh Token 갱신 완료 userId={} oldJwtId={} newJwtId={}", userId, oldJwtId, newRefreshJwtId);
        return new TokenPair(
                newAccessToken,
                newRefreshToken,
                jwtTokenClient.accessTokenTtl(),
                Duration.between(Instant.now(), newExpiresAt)
        );
    }

    private TokenPair buildGraceHitPair(Long userId, String role, RotateResult result, String oldJwtId) {
        String successorJwtId = result.graceSuccessorJwtId();
        Instant successorIssuedAt = result.graceSuccessorIssuedAt();
        Instant successorExpiresAt = result.graceSuccessorExpiresAt();

        String newAccessJwtId = UUID.randomUUID().toString();
        String newAccessToken = jwtTokenClient.generateAccessToken(userId, role, newAccessJwtId);
        String refreshToken = jwtTokenClient.generateRefreshToken(new RefreshTokenPayload(
                userId, role, successorJwtId, successorIssuedAt, successorExpiresAt
        ));

        log.info("유예 기간 내 구형 Refresh Token 수락 userId={} oldJwtId={} successorJwtId={}",
                userId, oldJwtId, successorJwtId);
        return new TokenPair(
                newAccessToken,
                refreshToken,
                jwtTokenClient.accessTokenTtl(),
                Duration.between(Instant.now(), successorExpiresAt)
        );
    }
}
