package com.readum.domain.user.service;

import com.readum.domain.exception.BadRequestException;
import com.readum.domain.user.dto.UpdateNicknameCommand;
import com.readum.domain.user.dto.UpdateNicknameResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UpdateNicknameServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UpdateNicknameService updateNicknameService;

    @Test
    void 닉네임을_정상적으로_수정한다() {
        User user = UserFixture.persistedUser(USER_ID, "any-session-id", "책읽는여우");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        UpdateNicknameResult result = updateNicknameService.execute(
                new UpdateNicknameCommand(USER_ID, "새닉네임"));

        assertThat(result.nickname()).isEqualTo("새닉네임");
        assertThat(user.getNickname()).isEqualTo("새닉네임");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345678901", "닉네임 공백", "특수문자!", "nick@name"})
    void 닉네임_형식이_올바르지_않으면_예외가_발생한다(String invalidNickname) {
        assertThatThrownBy(() -> updateNicknameService.execute(
                new UpdateNicknameCommand(USER_ID, invalidNickname)))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_NICKNAME);
    }
}
