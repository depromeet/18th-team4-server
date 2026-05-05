package com.readum.domain.user.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSearchService {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;

    @Transactional(readOnly = true)
    public Long findUserId(String sessionId) {
        return userRepository.findBySessionId(sessionId)
                .map(User::getId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));
    }

    @Transactional(readOnly = true)
    public UserSessionInfoResult findSessionInfo(String sessionId) {
        User user = userRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        boolean hasRegisteredBooks = userBookRepository.existsByUserId(user.getId());

        return new UserSessionInfoResult(
                user.getLastSelectedUserBookId(),
                hasRegisteredBooks,
                user.isOnboardingCompleted()
        );
    }
}
