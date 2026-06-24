package com.readum.domain.user.dto;

import com.readum.model.user.entity.User;

public record CompleteOnboardingResult(boolean onboardingCompleted) {

    public static CompleteOnboardingResult from(User user) {
        return new CompleteOnboardingResult(user.isOnboardingCompleted());
    }
}
