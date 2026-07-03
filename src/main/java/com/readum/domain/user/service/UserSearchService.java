package com.readum.domain.user.service;

import com.readum.domain.user.dto.UserProfileResult;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserSearchService {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;

    public UserSessionInfoResult findSessionInfo(Long userId) {
        User user = getUserByIdOrThrow(userId);

        boolean hasRegisteredBooks = userBookRepository.existsByUserId(user.getId());

        return UserSessionInfoResult.from(user, hasRegisteredBooks);
    }

    public UserProfileResult findProfile(Long userId) {
        User user = getUserByIdOrThrow(userId);
        return UserProfileResult.from(user);
    }

    // 인증 필터가 userId 의 실존을 이미 검증했다 — 빈 결과는 정상 흐름이 아니라 프로그램 버그.
    private User getUserByIdOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 userId 의 사용자가 존재하지 않음: userId=" + userId));
    }
}
