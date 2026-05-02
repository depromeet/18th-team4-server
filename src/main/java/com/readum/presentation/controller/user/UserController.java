package com.readum.presentation.controller.user;

import com.readum.domain.user.dto.CompleteOnboardingResult;
import com.readum.domain.user.dto.CreateUserSessionResult;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.domain.user.service.CompleteOnboardingService;
import com.readum.domain.user.service.CreateUserSessionService;
import com.readum.domain.user.service.UserSearchService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.user.dto.CompleteOnboardingResponse;
import com.readum.presentation.controller.user.dto.CreateUserSessionResponse;
import com.readum.presentation.controller.user.dto.UserSessionInfoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "사용자 세션", description = "사용자 세션 발급 / 조회 / 온보딩 완료 처리")
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

    @Operation(
            summary = "사용자 세션 생성",
            description = "신규 익명 세션을 생성하고 user_session 쿠키(HttpOnly, 365일)를 발급한다. " +
                    "이미 쿠키가 있어도 새로 발급된다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "세션 생성 성공")
    })
    @PostMapping("/sessions")
    public ResponseEntity<GlobalApiResponse<CreateUserSessionResponse>> createSession() {
        CreateUserSessionResult result = createUserSessionService.execute();

        ResponseCookie cookie = buildSessionCookie(result.sessionId().toString(), SESSION_COOKIE_MAX_AGE_SECONDS);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new GlobalApiResponse<>(CreateUserSessionResponse.from(result), null));
    }

    @Operation(
            summary = "현재 세션 정보 조회",
            description = "user_session 쿠키로 사용자의 세션 정보(온보딩 완료 여부 등)를 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "user_session 쿠키 누락"),
            @ApiResponse(responseCode = "404", description = "세션에 해당하는 사용자 미존재")
    })
    @GetMapping("/me")
    public ResponseEntity<GlobalApiResponse<UserSessionInfoResponse>> getSessionInfo(
            @CookieValue(name = USER_SESSION_COOKIE, required = true) String sessionId) {
        UserSessionInfoResult result = userSearchService.findSessionInfo(sessionId);
        return GlobalApiResponse.ok(UserSessionInfoResponse.from(result));
    }

    @Operation(
            summary = "온보딩 완료 처리",
            description = "현재 세션의 사용자에 대해 온보딩 완료 플래그를 true 로 갱신한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "온보딩 완료 처리 성공"),
            @ApiResponse(responseCode = "400", description = "user_session 쿠키 누락"),
            @ApiResponse(responseCode = "404", description = "세션에 해당하는 사용자 미존재")
    })
    @PostMapping("/me/onboarding")
    public ResponseEntity<GlobalApiResponse<CompleteOnboardingResponse>> completeOnboarding(
            @CookieValue(name = USER_SESSION_COOKIE, required = true) String sessionId) {
        CompleteOnboardingResult result = completeOnboardingService.execute(sessionId);
        return GlobalApiResponse.ok(CompleteOnboardingResponse.from(result));
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
