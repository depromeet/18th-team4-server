package com.readum.domain.user.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.UserProfileResult;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.domain.user.exception.UserErrorCode;
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

    public UserSessionInfoResult findSessionInfo(String sessionId) {
        User user = getUserBySessionIdOrThrow(sessionId);

        boolean hasRegisteredBooks = userBookRepository.existsByUserId(user.getId());

        return UserSessionInfoResult.from(user, hasRegisteredBooks);
    }

    public UserProfileResult findProfile(String sessionId) {
        User user = getUserBySessionIdOrThrow(sessionId);
        return UserProfileResult.from(user);
    }

    private User getUserBySessionIdOrThrow(String sessionId) {
        return userRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));
    }
}
