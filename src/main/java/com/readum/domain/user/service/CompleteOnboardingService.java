package com.readum.domain.user.service;

import com.readum.domain.user.dto.CompleteOnboardingResult;
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
    public CompleteOnboardingResult execute(Long userId) {
        // 인증 필터가 userId 의 실존을 이미 검증했다 — 빈 결과는 정상 흐름이 아니라 프로그램 버그.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 userId 의 사용자가 존재하지 않음: userId=" + userId));

        if (!user.isOnboardingCompleted()) {
            user.completeOnboarding();
        }

        return CompleteOnboardingResult.from(user);
    }
}
