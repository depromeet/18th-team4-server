package com.readum.presentation.controller.user.dto;

import com.readum.domain.user.dto.CompleteOnboardingResult;

public record CompleteOnboardingResponse(User user) {

    public record User(boolean onboardingCompleted) {}

    public static CompleteOnboardingResponse from(CompleteOnboardingResult result) {
        return new CompleteOnboardingResponse(new User(result.onboardingCompleted()));
    }
}
