package com.readum.infrastructure.auth.jpa;

import com.readum.domain.auth.dto.RefreshTokenRotation;
import com.readum.domain.auth.dto.RotateOutcome;
import com.readum.domain.auth.dto.RotateResult;
import com.readum.model.auth.entity.RefreshTokenEntity;
import com.readum.model.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class JpaRefreshTokenStoreTest {

    @Autowired
    private JpaRefreshTokenStore refreshTokenStore;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private static final Duration RT_TTL = Duration.ofHours(1);
    private static final Duration GRACE = Duration.ofSeconds(3);

    private Long userId;

    private static long userIdSeq = 800_000L;

    @BeforeEach
    void setUp() {
        userId = nextUserId();
    }

    @Test
    @DisplayName("ACTIVE row 를 rotate 하면 Rotated + successor 가 생성된다")
    void ACTIVE_rotate() {
        Instant now = Instant.now();
        String oldJti = "old-" + userId;
        String newJti = "new-" + userId;
        refreshTokenStore.save(userId, oldJti, now, now.plus(RT_TTL));

        RotateResult result = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, oldJti, newJti, now, now.plus(RT_TTL), GRACE
        ));

        assertThat(result.outcome()).isEqualTo(RotateOutcome.ROTATED);
        assertThat(refreshTokenRepository.findByParentJwtId(oldJti))
                .isPresent()
                .hasValueSatisfying(successor -> assertThat(successor.getJwtId()).isEqualTo(newJti));
    }

    @Test
    @DisplayName("IN_GRACE 상태에서 반복 rotate 는 매번 동일한 successor jti 를 반환하고 row 수가 증가하지 않는다")
    void IN_GRACE_반복_rotate() {
        Instant now = Instant.now();
        String oldJti = "old-" + userId;
        refreshTokenStore.save(userId, oldJti, now, now.plus(RT_TTL));

        RotateResult first = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, oldJti, "new-1-" + userId, now, now.plus(RT_TTL), GRACE
        ));
        long countAfterFirst = countUserRows();

        RotateResult second = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, oldJti, "new-2-" + userId, now, now.plus(RT_TTL), GRACE
        ));
        RotateResult third = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, oldJti, "new-3-" + userId, now, now.plus(RT_TTL), GRACE
        ));

        assertThat(first.outcome()).isEqualTo(RotateOutcome.ROTATED);
        assertThat(second.outcome()).isEqualTo(RotateOutcome.GRACE_HIT);
        assertThat(third.outcome()).isEqualTo(RotateOutcome.GRACE_HIT);
        assertThat(second.graceSuccessorJwtId()).isEqualTo(third.graceSuccessorJwtId());
        assertThat(countUserRows()).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("POST_GRACE 상태에서 old RT 로 rotate 시 ReuseDetected 와 함께 userId 전체가 폐기된다")
    void POST_GRACE_reuse_detected() {
        Instant now = Instant.now();
        String oldJti = "old-" + userId;
        refreshTokenRepository.save(RefreshTokenEntity.of(
                null, userId, oldJti, null,
                now.minusSeconds(120), now.plus(RT_TTL),
                now.minusSeconds(60), now.minusSeconds(30), null,
                LocalDateTime.now()
        ));
        refreshTokenRepository.flush();

        RotateResult result = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, oldJti, "new-" + userId, now, now.plus(RT_TTL), GRACE
        ));

        assertThat(result.outcome()).isEqualTo(RotateOutcome.REUSE_DETECTED);
        assertThat(refreshTokenRepository.findByUserIdAndJwtId(userId, oldJti))
                .hasValueSatisfying(row -> assertThat(row.isRevoked()).isTrue());
    }

    @Test
    @DisplayName("REVOKED row 로 rotate 시 ReuseDetected 를 반환한다")
    void REVOKED_reuse_detected() {
        Instant now = Instant.now();
        String oldJti = "old-" + userId;
        refreshTokenStore.save(userId, oldJti, now, now.plus(RT_TTL));
        refreshTokenStore.revokeAll(userId);

        RotateResult result = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, oldJti, "new-" + userId, now, now.plus(RT_TTL), GRACE
        ));

        assertThat(result.outcome()).isEqualTo(RotateOutcome.REUSE_DETECTED);
    }

    @Test
    @DisplayName("EXPIRED row 로 rotate 시 Expired 를 반환하고 userId 전체 폐기는 일어나지 않는다")
    void EXPIRED_는_전체_폐기_없음() {
        Instant now = Instant.now();
        String expiredJti = "old-" + userId;
        String activeJti = "active-" + userId;
        refreshTokenRepository.save(RefreshTokenEntity.of(
                null, userId, expiredJti, null,
                now.minusSeconds(3600), now.minusSeconds(10),
                null, null, null,
                LocalDateTime.now()
        ));
        refreshTokenStore.save(userId, activeJti, now, now.plus(RT_TTL));

        RotateResult result = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, expiredJti, "new-" + userId, now, now.plus(RT_TTL), GRACE
        ));

        assertThat(result.outcome()).isEqualTo(RotateOutcome.EXPIRED);
        assertThat(refreshTokenRepository.findByUserIdAndJwtId(userId, activeJti))
                .hasValueSatisfying(row -> assertThat(row.isRevoked()).isFalse());
    }

    @Test
    @DisplayName("NOT_FOUND: 저장되지 않은 jti 로 rotate 시 NotFound 를 반환한다")
    void NOT_FOUND() {
        Instant now = Instant.now();
        RotateResult result = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, "missing-" + userId, "new-" + userId, now, now.plus(RT_TTL), GRACE
        ));

        assertThat(result.outcome()).isEqualTo(RotateOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("revokeAll 호출 후 rotate 는 ReuseDetected 를 반환한다")
    void revokeAll_후_rotate() {
        Instant now = Instant.now();
        String oldJti = "old-" + userId;
        refreshTokenStore.save(userId, oldJti, now, now.plus(RT_TTL));
        refreshTokenStore.revokeAll(userId);

        RotateResult result = refreshTokenStore.rotate(new RefreshTokenRotation(
                userId, oldJti, "new-" + userId, now, now.plus(RT_TTL), GRACE
        ));

        assertThat(result.outcome()).isEqualTo(RotateOutcome.REUSE_DETECTED);
    }

    private long countUserRows() {
        List<RefreshTokenEntity> all = refreshTokenRepository.findAll();
        return all.stream().filter(e -> e.getUserId().equals(userId)).count();
    }

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }
}
