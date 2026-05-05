package com.readum.presentation.controller.user;

import com.readum.domain.user.dto.CompleteOnboardingResult;
import com.readum.domain.user.dto.CreateUserSessionResult;
import com.readum.domain.user.dto.UserSessionInfoResult;
import com.readum.domain.user.service.CompleteOnboardingService;
import com.readum.domain.user.service.CreateUserSessionService;
import com.readum.domain.user.service.UserSearchService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.user.dto.CompleteOnboardingResponse;
import com.readum.presentation.controller.user.dto.CreateUserSessionResponse;
import com.readum.presentation.controller.user.dto.UserSessionInfoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    @Operation(
            summary = "익명 사용자 세션 생성",
            description = "UUID 기반 세션 ID를 발급하고 user_session 쿠키에 설정한다. " +
                    "소셜 로그인 전 익명 사용자 식별에 사용된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "세션 생성 성공"),
    })
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<CreateUserSessionResponse>> createSession() {
        CreateUserSessionResult result = createUserSessionService.execute();

        ResponseCookie cookie = buildSessionCookie(result.sessionId().toString(), SESSION_COOKIE_MAX_AGE_SECONDS);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new ApiResponse<>(CreateUserSessionResponse.from(result), null));
    }

    @Operation(
            summary = "세션 정보 조회",
            description = "user_session 쿠키로 사용자를 식별하고 온보딩 완료 여부, 책장 도서 등록 여부, " +
                    "마지막 선택 도서 ID를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "user_session 쿠키 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 세션"),
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserSessionInfoResponse>> getSessionInfo(
            @CookieValue(name = USER_SESSION_COOKIE, required = true) String sessionId) {
        UserSessionInfoResult result = userSearchService.findSessionInfo(sessionId);
        return ApiResponse.ok(UserSessionInfoResponse.from(result));
    }

    @Operation(
            summary = "온보딩 완료 처리",
            description = "user_session 쿠키로 사용자를 식별하고 온보딩 완료 상태로 전환한다. " +
                    "이미 완료된 경우에도 멱등 처리되어 200을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "온보딩 완료 처리 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "user_session 쿠키 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 세션"),
    })
    @PostMapping("/me/onboarding")
    public ResponseEntity<ApiResponse<CompleteOnboardingResponse>> completeOnboarding(
            @CookieValue(name = USER_SESSION_COOKIE, required = true) String sessionId) {
        CompleteOnboardingResult result = completeOnboardingService.execute(sessionId);
        return ApiResponse.ok(CompleteOnboardingResponse.from(result));
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
