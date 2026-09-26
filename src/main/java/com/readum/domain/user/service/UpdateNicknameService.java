package com.readum.domain.user.service;

import com.readum.domain.exception.BadRequestException;
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

        // 인증 필터가 userId 의 실존을 이미 검증했다 — 빈 결과는 정상 흐름이 아니라 프로그램 버그.
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalStateException(
                        "인증된 userId 의 사용자가 존재하지 않음: userId=" + command.userId()));

        user.updateNickname(command.nickname());

        return UpdateNicknameResult.from(user);
    }
}
