package com.readum.domain.user.dto;

import com.readum.model.user.entity.User;

public record UserProfileResult(String nickname) {

    public static UserProfileResult from(User user) {
        return new UserProfileResult(user.getNickname());
    }
}
