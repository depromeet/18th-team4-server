package com.readum.domain.user.service;

import com.readum.domain.user.dto.UserProfileResult;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.model.userBook.repository.UserBookRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    void 인증된_사용자면_세션_정보를_반환한다() {
        User user = UserFixture.persistedUser(10L, "any-session-id", 7L, false);

        given(userRepository.findById(10L)).willReturn(Optional.of(user));
        given(userBookRepository.existsByUserId(10L)).willReturn(true);

        UserSessionInfoResult result = userSearchService.findSessionInfo(10L);

        assertThat(result.lastSelectedUserBookId()).isEqualTo(7L);
        assertThat(result.hasRegisteredBooks()).isTrue();
        assertThat(result.onboardingCompleted()).isFalse();
    }

    @Test
    void 등록된_도서가_없으면_hasRegisteredBooks_가_false_이다() {
        User user = UserFixture.persistedUser(11L, "any-session-id", null, true);

        given(userRepository.findById(11L)).willReturn(Optional.of(user));
        given(userBookRepository.existsByUserId(11L)).willReturn(false);

        UserSessionInfoResult result = userSearchService.findSessionInfo(11L);

        assertThat(result.lastSelectedUserBookId()).isNull();
        assertThat(result.hasRegisteredBooks()).isFalse();
        assertThat(result.onboardingCompleted()).isTrue();
    }

    @Test
    void 인증된_사용자면_프로필_닉네임을_반환한다() {
        User user = UserFixture.persistedUser(20L, "any-session-id", "문장수집가");

        given(userRepository.findById(20L)).willReturn(Optional.of(user));

        UserProfileResult result = userSearchService.findProfile(20L);

        assertThat(result.nickname()).isEqualTo("문장수집가");
    }

    @Test
    void 닉네임이_없는_기존_사용자의_프로필은_닉네임이_null_이다() {
        User user = UserFixture.persistedUser(21L, "any-session-id", (String) null);

        given(userRepository.findById(21L)).willReturn(Optional.of(user));

        UserProfileResult result = userSearchService.findProfile(21L);

        assertThat(result.nickname()).isNull();
    }
}
