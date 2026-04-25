package com.readum.presentation.controller.user;

import com.readum.domain.user.dto.CreateUserSessionResult;
import com.readum.domain.user.service.CreateUserSessionService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.user.dto.CreateUserSessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
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
