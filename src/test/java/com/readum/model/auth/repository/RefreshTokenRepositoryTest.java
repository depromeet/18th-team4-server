package com.readum.model.auth.repository;

import com.readum.model.auth.entity.RefreshToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("save 이후 userId + jwtId 로 조회할 수 있다")
    void save_이후_조회() {
        Long userId = nextUserId();
        Instant now = Instant.now();
        RefreshToken saved = refreshTokenRepository.save(
                RefreshToken.create(userId, "jti-" + userId, now, now.plusSeconds(3600))
        );

        Optional<RefreshToken> found = refreshTokenRepository.findByUserIdAndJwtId(userId, "jti-" + userId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("active row 에 대한 rotate CAS 는 첫 번째만 affected=1, 두 번째는 0 을 반환한다")
    void 연속_rotate_CAS_는_한_번만_성공한다() {
        Long userId = nextUserId();
        Instant now = Instant.now();
        RefreshToken row = refreshTokenRepository.save(
                RefreshToken.create(userId, "jti-" + userId, now, now.plusSeconds(3600))
        );

        int first = refreshTokenRepository.rotate(row.getId(), now, now.plusSeconds(3));
        int second = refreshTokenRepository.rotate(row.getId(), now, now.plusSeconds(3));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("expires_at 이 경과된 row 에 대한 rotate CAS 는 affected=0")
    void 만료된_row_는_rotate_실패() {
        Long userId = nextUserId();
        Instant past = Instant.now().minusSeconds(10);
        RefreshToken row = refreshTokenRepository.save(
                RefreshToken.create(userId, "jti-" + userId, past.minusSeconds(60), past)
        );

        int affected = refreshTokenRepository.rotate(row.getId(), Instant.now(), Instant.now().plusSeconds(3));

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("revokeAllByUserId 는 revoked_at 이 null 인 row 만 폐기한다")
    void revokeAllByUserId_는_활성_row_만_폐기() {
        Long userId = nextUserId();
        Instant now = Instant.now();
        refreshTokenRepository.save(
                RefreshToken.create(userId, "jti-a-" + userId, now, now.plusSeconds(3600))
        );
        refreshTokenRepository.save(
                RefreshToken.create(userId, "jti-b-" + userId, now, now.plusSeconds(3600))
        );

        int affected = refreshTokenRepository.revokeAllByUserId(userId, now);

        assertThat(affected).isEqualTo(2);

        int second = refreshTokenRepository.revokeAllByUserId(userId, now);
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("parent_jwt_id 로 child 를 역조회할 수 있다")
    void parent_jwt_id_역조회() {
        Long userId = nextUserId();
        Instant now = Instant.now();
        String parentJti = "parent-" + userId;
        String childJti = "child-" + userId;

        refreshTokenRepository.save(
                RefreshToken.create(userId, parentJti, now, now.plusSeconds(3600))
        );
        refreshTokenRepository.save(
                RefreshToken.createChild(userId, childJti, parentJti, now, now.plusSeconds(3600))
        );

        Optional<RefreshToken> found = refreshTokenRepository.findByParentJwtId(parentJti);

        assertThat(found).isPresent();
        assertThat(found.get().getJwtId()).isEqualTo(childJti);
    }

    private static long userIdSeq = 900_000L;

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }
}
