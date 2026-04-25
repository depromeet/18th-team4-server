package com.readum.presentation.controller.user;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.dto.CompleteOnboardingResult;
import com.readum.domain.user.dto.CreateUserSessionResult;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.domain.user.service.CompleteOnboardingService;
import com.readum.domain.user.service.CreateUserSessionService;
import com.readum.domain.user.service.UserSearchService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.user.dto.CompleteOnboardingResponse;
import com.readum.presentation.controller.user.dto.CreateUserSessionResponse;
import com.readum.presentation.controller.user.dto.UserSessionInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    static final String USER_SESSION_COOKIE = "user_session";
    private static final long SESSION_COOKIE_MAX_AGE_SECONDS = Duration.ofDays(365).toSeconds();

    private final CreateUserSessionService createUserSessionService;
    private final UserSearchService userSearchService;
    private final CompleteOnboardingService completeOnboardingService;

    @Value("${user.session-cookie-secure:true}")
    private boolean sessionCookieSecure;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<CreateUserSessionResponse>> createSession() {
        CreateUserSessionResult result = createUserSessionService.execute();

        ResponseCookie cookie = buildSessionCookie(result.sessionId().toString(), SESSION_COOKIE_MAX_AGE_SECONDS);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new ApiResponse<>(CreateUserSessionResponse.from(result), null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserSessionInfoResponse>> getSessionInfo(
            @CookieValue(name = USER_SESSION_COOKIE, required = false) String sessionId) {
        String resolvedSessionId = requireSessionId(sessionId);
        UserSessionInfoResult result = userSearchService.findSessionInfo(resolvedSessionId);
        return ApiResponse.ok(UserSessionInfoResponse.from(result));
    }

    @PostMapping("/me/onboarding")
    public ResponseEntity<ApiResponse<CompleteOnboardingResponse>> completeOnboarding(
            @CookieValue(name = USER_SESSION_COOKIE, required = false) String sessionId) {
        String resolvedSessionId = requireSessionId(sessionId);
        CompleteOnboardingResult result = completeOnboardingService.execute(resolvedSessionId);
        return ApiResponse.ok(CompleteOnboardingResponse.from(result));
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new UnauthorizedException(UserErrorCode.INVALID_SESSION);
        }
        return sessionId;
    }

    private ResponseCookie buildSessionCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(USER_SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(sessionCookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
