package com.readum.presentation.controller.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.readum.domain.user.dto.UserSessionInfoResult;

public record UserSessionInfoResponse(Session session) {

    public record Session(
            @JsonInclude(JsonInclude.Include.ALWAYS) Long lastSelectedUserBookId,
            boolean hasRegisteredBooks,
            boolean onboardingCompleted
    ) {}

    public static UserSessionInfoResponse from(UserSessionInfoResult result) {
        return new UserSessionInfoResponse(new Session(
                result.lastSelectedUserBookId(),
                result.hasRegisteredBooks(),
                result.onboardingCompleted()
        ));
    }
}
