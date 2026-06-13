package com.readum.presentation.controller.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.readum.domain.user.dto.UserProfileResult;

public record UserProfileResponse(Profile profile) {

    public record Profile(
            // 닉네임이 없는 기존 사용자도 키는 항상 노출(값은 null)되도록 강제한다.
            @JsonInclude(JsonInclude.Include.ALWAYS) String nickname
    ) {}

    public static UserProfileResponse from(UserProfileResult result) {
        return new UserProfileResponse(new Profile(result.nickname()));
    }
}
