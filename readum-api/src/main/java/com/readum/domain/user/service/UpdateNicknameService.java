package com.readum.domain.user.service;

import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.UpdateNicknameCommand;
import com.readum.domain.user.dto.UpdateNicknameResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UpdateNicknameService {

    private static final Pattern NICKNAME_PATTERN = Pattern.compile("^[A-Za-z0-9가-힣]{1,10}$");

    private final UserRepository userRepository;

    @Transactional
    public UpdateNicknameResult execute(UpdateNicknameCommand command) {
        if (!NICKNAME_PATTERN.matcher(command.nickname()).matches()) {
            throw new BadRequestException(UserErrorCode.INVALID_NICKNAME);
        }

        User user = userRepository.findBySessionId(command.sessionId())
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        user.updateNickname(command.nickname());

        return UpdateNicknameResult.from(user);
    }
}
