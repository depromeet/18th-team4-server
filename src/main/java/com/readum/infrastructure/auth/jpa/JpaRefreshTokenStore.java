package com.readum.infrastructure.auth.jpa;

import com.readum.domain.auth.dto.RefreshTokenRotation;
import com.readum.domain.auth.dto.RotateResult;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.model.auth.entity.RefreshTokenEntity;
import com.readum.model.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        refreshTokenRepository.saveAndFlush(RefreshTokenEntity.create(userId, jwtId, issuedAt, expiresAt));
    }

    @Override
    @Transactional
    public RotateResult rotate(RefreshTokenRotation rotation) {
        return doRotate(rotation);
    }

    @Override
    @Transactional
    public void revokeAll(Long userId) {
        int affected = refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
        log.info("Refresh Token 전체 폐기 userId={} affected={}", userId, affected);
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
            log.info("이미 폐기된 Refresh Token 으로 refresh 시도 userId={} jwtId={}", rotation.userId(), rotation.oldJwtId());
            return RotateResult.reuseDetected();
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
                .orElseThrow(() -> {
                    log.error("데이터 정합성 오류 - Grace 상태의 Refresh Token 에 대응하는 successor 가 존재하지 않음 oldJwtId={}", oldJwtId);
                    return new IllegalStateException("grace state without successor: " + oldJwtId);
                });
    }

    private RotateResult revokeAndReport(Long userId, Instant now) {
        int affected = refreshTokenRepository.revokeAllByUserId(userId, now);
        log.warn("Refresh Token 재사용 감지로 전체 폐기 userId={} affected={}", userId, affected);
        return RotateResult.reuseDetected();
    }
}
