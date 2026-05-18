package com.readum.domain.user.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.CompleteOnboardingResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CompleteOnboardingServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CompleteOnboardingService completeOnboardingService;

    @Test
    void 미완료_사용자에_대해_온보딩을_완료_상태로_변경한다() {
        UUID sessionId = UUID.randomUUID();
        User user = User.create(sessionId);

        given(userRepository.findBySessionId(sessionId.toString())).willReturn(Optional.of(user));

        CompleteOnboardingResult result = completeOnboardingService.execute(sessionId.toString());

        assertThat(result.onboardingCompleted()).isTrue();
        assertThat(user.isOnboardingCompleted()).isTrue();
    }

    @Test
    void 이미_완료된_사용자에_대해_멱등하게_true_를_반환한다() {
        UUID sessionId = UUID.randomUUID();
        User user = User.create(sessionId);
        user.completeOnboarding();
        LocalDateTime previousUpdatedAt = user.getUpdatedAt();

        given(userRepository.findBySessionId(sessionId.toString())).willReturn(Optional.of(user));

        CompleteOnboardingResult result = completeOnboardingService.execute(sessionId.toString());

        assertThat(result.onboardingCompleted()).isTrue();
        assertThat(user.isOnboardingCompleted()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(previousUpdatedAt);
    }

    @Test
    void 존재하지_않는_세션이면_INVALID_SESSION_예외가_발생한다() {
        String unknown = UUID.randomUUID().toString();
        given(userRepository.findBySessionId(unknown)).willReturn(Optional.empty());

        assertThatThrownBy(() -> completeOnboardingService.execute(unknown))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }
}
