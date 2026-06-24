package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.dto.UpdateNicknameCommand;

public record UpdateNicknameRequest(String nickname) {

    public UpdateNicknameCommand toCommand(String sessionId) {
        return new UpdateNicknameCommand(sessionId, nickname);
    }
}
