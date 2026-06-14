package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.dto.UpdateNicknameResult;

public record UpdateNicknameResponse(User user) {

    public record User(String nickname) {}

    public static UpdateNicknameResponse from(UpdateNicknameResult result) {
        return new UpdateNicknameResponse(new User(result.nickname()));
    }
}
