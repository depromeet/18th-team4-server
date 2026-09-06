package com.readum.domain.auth.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * user_session 쿠키 값을 사용자 신원(userId)으로 해석한다.
 * 신원 해석은 이 서비스와 이를 호출하는 인증 필터(SessionCookieAuthenticationFilter) 한 경로로만
 * 일어나도록 규칙을 둔다 — UserRepository.findBySessionId 의 허용 호출자는 이 서비스 하나다.
 * (기존 서비스들의 직접 호출은 이 브랜치에서 제거되며, 규칙은 AuthenticationBoundaryArchTest 의
 * ArchUnit 규칙으로 강제된다.)
 * 소셜 로그인(JWT) 도입 시 TokenAuthenticationService 와 나란히 게스트 인증 담당으로 남는다.
 */
@Service
@RequiredArgsConstructor
public class SessionAuthenticationService {

    private final UserRepository userRepository;

    public Long authenticate(String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));
        return user.getId();
    }
}
