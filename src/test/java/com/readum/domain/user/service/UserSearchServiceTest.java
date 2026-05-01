package com.readum.domain.user.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.repository.UserBookRepository;
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
class UserSearchServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @InjectMocks
    private UserSearchService userSearchService;

    @Test
    void 유효한_세션이면_세션_정보를_반환한다() {
        UUID sessionId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        User user = User.of(10L, null, sessionId.toString(), 7L, false, now, now);

        given(userRepository.findBySessionId(sessionId.toString())).willReturn(Optional.of(user));
        given(userBookRepository.existsByUserId(10L)).willReturn(true);

        UserSessionInfoResult result = userSearchService.findSessionInfo(sessionId.toString());

        assertThat(result.lastSelectedUserBookId()).isEqualTo(7L);
        assertThat(result.hasRegisteredBooks()).isTrue();
        assertThat(result.onboardingCompleted()).isFalse();
    }

    @Test
    void 등록된_도서가_없으면_hasRegisteredBooks_가_false_이다() {
        UUID sessionId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        User user = User.of(11L, null, sessionId.toString(), null, true, now, now);

        given(userRepository.findBySessionId(sessionId.toString())).willReturn(Optional.of(user));
        given(userBookRepository.existsByUserId(11L)).willReturn(false);

        UserSessionInfoResult result = userSearchService.findSessionInfo(sessionId.toString());

        assertThat(result.lastSelectedUserBookId()).isNull();
        assertThat(result.hasRegisteredBooks()).isFalse();
        assertThat(result.onboardingCompleted()).isTrue();
    }

    @Test
    void 존재하지_않는_세션이면_INVALID_SESSION_예외가_발생한다() {
        String unknown = UUID.randomUUID().toString();
        given(userRepository.findBySessionId(unknown)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userSearchService.findSessionInfo(unknown))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }
}
