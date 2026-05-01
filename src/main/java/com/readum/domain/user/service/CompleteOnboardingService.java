package com.readum.domain.user.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.CompleteOnboardingResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompleteOnboardingService {

    private final UserRepository userRepository;

    @Transactional
    public CompleteOnboardingResult execute(String sessionId) {
        User user = userRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        if (!user.isOnboardingCompleted()) {
            user.completeOnboarding();
        }

        return new CompleteOnboardingResult(user.isOnboardingCompleted());
    }
}
