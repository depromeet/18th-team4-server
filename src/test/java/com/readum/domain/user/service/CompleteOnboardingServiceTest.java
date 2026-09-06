package com.readum.domain.user.service;

import com.readum.domain.user.dto.CompleteOnboardingResult;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CompleteOnboardingServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CompleteOnboardingService completeOnboardingService;

    @Test
    void 미완료_사용자에_대해_온보딩을_완료_상태로_변경한다() {
        User user = User.create(UUID.randomUUID(), "책읽는여우");

        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        CompleteOnboardingResult result = completeOnboardingService.execute(USER_ID);

        assertThat(result.onboardingCompleted()).isTrue();
        assertThat(user.isOnboardingCompleted()).isTrue();
    }

    @Test
    void 이미_완료된_사용자에_대해_멱등하게_true_를_반환한다() {
        User user = User.create(UUID.randomUUID(), "책읽는여우");
        user.completeOnboarding();
        LocalDateTime previousUpdatedAt = user.getUpdatedAt();

        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        CompleteOnboardingResult result = completeOnboardingService.execute(USER_ID);

        assertThat(result.onboardingCompleted()).isTrue();
        assertThat(user.isOnboardingCompleted()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(previousUpdatedAt);
    }
}
