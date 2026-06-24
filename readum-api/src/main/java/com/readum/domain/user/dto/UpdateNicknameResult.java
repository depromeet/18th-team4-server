package com.readum.domain.user.dto;

import com.readum.model.user.entity.User;

public record UpdateNicknameResult(String nickname) {

    public static UpdateNicknameResult from(User user) {
        return new UpdateNicknameResult(user.getNickname());
    }
}
