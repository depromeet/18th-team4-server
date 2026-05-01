package com.readum.domain.user.service;

import com.readum.domain.user.dto.CreateUserSessionResult;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateUserSessionServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CreateUserSessionService createUserSessionService;

    @Test
    void 신규_사용자를_저장하고_세션_식별자를_반환한다() {
        given(userRepository.save(org.mockito.ArgumentMatchers.any(User.class))).willAnswer(invocation -> {
            User input = invocation.getArgument(0);
            LocalDateTime now = LocalDateTime.now();
            return User.of(
                    1L,
                    null,
                    input.getSessionId(),
                    null,
                    false,
                    now,
                    now
            );
        });

        CreateUserSessionResult result = createUserSessionService.execute();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User savedArg = captor.getValue();
        assertThat(savedArg.getSessionId()).isEqualTo(result.sessionId().toString());
        assertThat(savedArg.isOnboardingCompleted()).isFalse();
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.sessionId()).isInstanceOf(UUID.class);
        assertThat(result.createdAt()).isNotNull();
    }
}
