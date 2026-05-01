package com.readum.domain.user.dto;

public record UserSessionInfoResult(
        Long lastSelectedUserBookId,
        boolean hasRegisteredBooks,
        boolean onboardingCompleted
) {
}
