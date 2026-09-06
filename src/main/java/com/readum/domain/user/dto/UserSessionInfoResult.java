package com.readum.domain.user.dto;

import com.readum.model.user.entity.User;

public record UserSessionInfoResult(
        Long lastSelectedUserBookId,
        boolean hasRegisteredBooks,
        boolean onboardingCompleted
) {

    public static UserSessionInfoResult from(User user, boolean hasRegisteredBooks) {
        return new UserSessionInfoResult(
                user.getLastSelectedUserBookId(),
                hasRegisteredBooks,
                user.isOnboardingCompleted()
        );
    }
}
