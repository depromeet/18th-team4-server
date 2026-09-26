package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.dto.UpdateNicknameCommand;

public record UpdateNicknameRequest(String nickname) {

    public UpdateNicknameCommand toCommand(Long userId) {
        return new UpdateNicknameCommand(userId, nickname);
    }
}
