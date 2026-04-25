package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.dto.RotateResult;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.dto.TokenRefreshCommand;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.auth.jwt.JwtTokenProvider;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.model.auth.entity.RefreshToken;
import com.readum.model.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public TokenPair execute(TokenRefreshCommand command) {
        ParsedToken parsed = jwtTokenProvider.parse(command.refreshToken());
        if (parsed.type() != TokenType.REFRESH) {
            throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
        }

        Long userId = parsed.userId();
        String role = parsed.role();
        String oldJwtId = parsed.jwtId();

        Instant now = Instant.now();
        String newRefreshJwtId = UUID.randomUUID().toString();
        Instant newRefreshExpiresAt = now.plus(jwtTokenProvider.refreshTokenTtl());
        Duration gracePeriod = jwtTokenProvider.refreshGracePeriod();

        RotateResult result = rotate(userId, oldJwtId, newRefreshJwtId, now, newRefreshExpiresAt, gracePeriod);

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

    private RotateResult rotate(
            Long userId, String oldJwtId, String newJwtId,
            Instant newIssuedAt, Instant newExpiresAt, Duration gracePeriod
    ) {
        Optional<RefreshToken> found = refreshTokenRepository.findByUserIdAndJwtId(userId, oldJwtId);

        if (found.isEmpty()) {
            return RotateResult.notFound();
        }

        RefreshToken row = found.get();

        if (row.isRevoked()) {
            log.info("이미 폐기된 Refresh Token 으로 refresh 시도 userId={} jwtId={}", userId, oldJwtId);
            return RotateResult.reuseDetected();
        }
        if (row.isExpired(newIssuedAt)) {
            return RotateResult.expired();
        }
        if (row.isInGrace(newIssuedAt)) {
            return graceHitFor(oldJwtId);
        }
        if (row.getRotatedAt() != null) {
            return revokeAndReport(userId, newIssuedAt);
        }

        int affected = refreshTokenRepository.rotate(row.getId(), newIssuedAt, newIssuedAt.plus(gracePeriod));
        if (affected == 1) {
            refreshTokenRepository.saveAndFlush(RefreshToken.createChild(
                    userId, newJwtId, oldJwtId, newIssuedAt, newExpiresAt
            ));
            return RotateResult.rotated();
        }

        return reclassifyAfterCasFailure(userId, oldJwtId, newIssuedAt);
    }

    private RotateResult reclassifyAfterCasFailure(Long userId, String oldJwtId, Instant now) {
        RefreshToken refreshed = refreshTokenRepository
                .findByUserIdAndJwtId(userId, oldJwtId)
                .orElse(null);

        if (refreshed == null) {
            return RotateResult.notFound();
        }
        if (refreshed.isRevoked()) {
            return RotateResult.reuseDetected();
        }
        if (refreshed.isExpired(now)) {
            return RotateResult.expired();
        }
        if (refreshed.isInGrace(now)) {
            return graceHitFor(oldJwtId);
        }
        return revokeAndReport(userId, now);
    }

    private RotateResult graceHitFor(String oldJwtId) {
        return refreshTokenRepository.findByParentJwtId(oldJwtId)
                .<RotateResult>map(child -> RotateResult.graceHit(
                        child.getJwtId(),
                        child.getIssuedAt(),
                        child.getExpiresAt()
                ))
                .orElseThrow(() -> {
                    log.error("데이터 정합성 오류 - Grace 상태의 Refresh Token 에 대응하는 child 가 존재하지 않음 oldJwtId={}", oldJwtId);
                    return new IllegalStateException("grace state without child: " + oldJwtId);
                });
    }

    private RotateResult revokeAndReport(Long userId, Instant now) {
        int affected = refreshTokenRepository.revokeAllByUserId(userId, now);
        log.warn("Refresh Token 재사용 감지로 전체 폐기 userId={} affected={}", userId, affected);
        return RotateResult.reuseDetected();
    }

    private TokenPair buildRotatedPair(
            Long userId, String role, String newRefreshJwtId,
            Instant newIssuedAt, Instant newExpiresAt, String oldJwtId
    ) {
        String newAccessJwtId = UUID.randomUUID().toString();
        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, role, newAccessJwtId);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(new RefreshTokenPayload(
                userId, role, newRefreshJwtId, newIssuedAt, newExpiresAt
        ));

        log.info("Refresh Token 갱신 완료 userId={} oldJwtId={} newJwtId={}", userId, oldJwtId, newRefreshJwtId);
        return new TokenPair(
                newAccessToken,
                newRefreshToken,
                jwtTokenProvider.accessTokenTtl(),
                Duration.between(Instant.now(), newExpiresAt)
        );
    }

    private TokenPair buildGraceHitPair(Long userId, String role, RotateResult result, String oldJwtId) {
        String childJwtId = result.graceChildJwtId();
        Instant childIssuedAt = result.graceChildIssuedAt();
        Instant childExpiresAt = result.graceChildExpiresAt();

        String newAccessJwtId = UUID.randomUUID().toString();
        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, role, newAccessJwtId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(new RefreshTokenPayload(
                userId, role, childJwtId, childIssuedAt, childExpiresAt
        ));

        log.info("유예 기간 내 구형 Refresh Token 수락 userId={} oldJwtId={} childJwtId={}",
                userId, oldJwtId, childJwtId);
        return new TokenPair(
                newAccessToken,
                refreshToken,
                jwtTokenProvider.accessTokenTtl(),
                Duration.between(Instant.now(), childExpiresAt)
        );
    }
}
