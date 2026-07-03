package com.readum.domain.auth.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SessionAuthenticationServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SessionAuthenticationService sessionAuthenticationService;

    @Test
    void 유효한_세션이면_해당_사용자의_userId_를_반환한다() {
        User user = UserFixture.persistedUser(7L, "valid-session-id");
        given(userRepository.findBySessionId("valid-session-id")).willReturn(Optional.of(user));

        Long userId = sessionAuthenticationService.authenticate("valid-session-id");

        assertThat(userId).isEqualTo(7L);
    }

    @Test
    void 존재하지_않는_세션이면_INVALID_SESSION_예외가_발생한다() {
        given(userRepository.findBySessionId("unknown-session-id")).willReturn(Optional.empty());

        assertThatThrownBy(() -> sessionAuthenticationService.authenticate("unknown-session-id"))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }
}
