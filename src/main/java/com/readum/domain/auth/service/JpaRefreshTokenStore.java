package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.RefreshTokenRotation;
import com.readum.domain.auth.dto.RotateResult;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.model.auth.entity.RefreshTokenEntity;
import com.readum.model.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class JpaRefreshTokenStore implements RefreshTokenStore {

    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    @Transactional
    public void save(Long userId, String jwtId, Instant issuedAt, Instant expiresAt) {
        try {
            refreshTokenRepository.saveAndFlush(RefreshTokenEntity.create(userId, jwtId, issuedAt, expiresAt));
        } catch (DataAccessException e) {
            log.error("DB 장애 - Refresh Token 저장 불가 userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    @Transactional
    public RotateResult rotate(RefreshTokenRotation rotation) {
        try {
            return doRotate(rotation);
        } catch (DataAccessException e) {
            log.error("DB 장애 - Refresh Token 회전 불가 userId={} oldJwtId={}",
                    rotation.userId(), rotation.oldJwtId(), e);
            throw new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    @Transactional
    public void revokeAll(Long userId) {
        try {
            int affected = refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
            log.info("Refresh Token 전체 폐기 userId={} affected={}", userId, affected);
        } catch (DataAccessException e) {
            log.error("DB 장애 - Refresh Token 폐기 불가 userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private RotateResult doRotate(RefreshTokenRotation rotation) {
        Instant now = rotation.newIssuedAt();
        Optional<RefreshTokenEntity> found = refreshTokenRepository
                .findByUserIdAndJwtId(rotation.userId(), rotation.oldJwtId());

        if (found.isEmpty()) {
            return RotateResult.notFound();
        }

        RefreshTokenEntity row = found.get();

        if (row.isRevoked()) {
            return revokeAndReport(rotation.userId(), now);
        }
        if (row.isExpired(now)) {
            return RotateResult.expired();
        }
        if (row.isInGrace(now)) {
            return graceHitFor(rotation.oldJwtId());
        }
        if (row.getRotatedAt() != null) {
            return revokeAndReport(rotation.userId(), now);
        }

        int affected = refreshTokenRepository.rotate(row.getId(), now, now.plus(rotation.gracePeriod()));
        if (affected == 1) {
            refreshTokenRepository.saveAndFlush(RefreshTokenEntity.createSuccessor(
                    rotation.userId(),
                    rotation.newJwtId(),
                    rotation.oldJwtId(),
                    rotation.newIssuedAt(),
                    rotation.newExpiresAt()
            ));
            return RotateResult.rotated();
        }

        return reclassifyAfterCasFailure(rotation, now);
    }

    private RotateResult reclassifyAfterCasFailure(RefreshTokenRotation rotation, Instant now) {
        RefreshTokenEntity refreshed = refreshTokenRepository
                .findByUserIdAndJwtId(rotation.userId(), rotation.oldJwtId())
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
            return graceHitFor(rotation.oldJwtId());
        }
        return revokeAndReport(rotation.userId(), now);
    }

    private RotateResult graceHitFor(String oldJwtId) {
        return refreshTokenRepository.findByParentJwtId(oldJwtId)
                .<RotateResult>map(successor -> RotateResult.graceHit(
                        successor.getJwtId(),
                        successor.getIssuedAt(),
                        successor.getExpiresAt()
                ))
                .orElseGet(RotateResult::notFound);
    }

    private RotateResult revokeAndReport(Long userId, Instant now) {
        int affected = refreshTokenRepository.revokeAllByUserId(userId, now);
        log.warn("Refresh Token 재사용 감지로 전체 폐기 userId={} affected={}", userId, affected);
        return RotateResult.reuseDetected();
    }
}
