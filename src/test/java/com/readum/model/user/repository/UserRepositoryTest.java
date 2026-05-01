package com.readum.model.user.repository;

import com.readum.model.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("sessionId 로 저장한 사용자를 조회할 수 있다")
    void sessionId_로_조회() {
        UUID sessionId = UUID.randomUUID();
        User saved = userRepository.save(User.create(sessionId));

        Optional<User> found = userRepository.findBySessionId(sessionId.toString());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getSessionId()).isEqualTo(sessionId.toString());
        assertThat(found.get().isOnboardingCompleted()).isFalse();
        assertThat(found.get().getLastSelectedUserBookId()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 sessionId 로 조회하면 빈 Optional 을 반환한다")
    void 존재하지_않는_sessionId_조회() {
        Optional<User> found = userRepository.findBySessionId(UUID.randomUUID().toString());

        assertThat(found).isEmpty();
    }
}
