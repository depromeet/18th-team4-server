package com.readum.presentation.controller.auth;

import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.auth.service.LogoutService;
import com.readum.domain.auth.service.TokenRefreshService;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.common.security.JwtAuthenticationFilter;
import com.readum.presentation.controller.auth.dto.LogoutRequest;
import com.readum.presentation.controller.auth.dto.TokenRefreshRequest;
import com.readum.presentation.controller.auth.dto.TokenRefreshResponse;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final TokenRefreshService tokenRefreshService;
    private final LogoutService logoutService;

    @Value("${jwt.refresh-cookie-secure:true}")
    private boolean refreshCookieSecure;

    @Operation(
            summary = "액세스 토큰 갱신",
            description = "refresh_token 쿠키를 검증하고 새 액세스 토큰과 리프레시 토큰을 발급한다. " +
                    "토큰이 만료·폐기·재사용 감지된 경우 401을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 갱신 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "refresh_token 쿠키 누락"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "토큰 무효·만료·재사용 감지"),
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE) String refreshToken) {
        TokenRefreshRequest request = new TokenRefreshRequest(refreshToken);
        TokenPair pair = tokenRefreshService.execute(request.toCommand());

        ResponseCookie cookie = buildRefreshTokenCookie(pair.refreshToken(), pair.refreshTokenTtl().toSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new ApiResponse<>(TokenRefreshResponse.from(pair), null));
    }

    @Operation(
            summary = "로그아웃",
            description = "리프레시 토큰을 폐기하고 액세스 토큰을 블랙리스트에 등록한다. " +
                    "쿠키가 없거나 유효하지 않은 토큰이어도 멱등 처리되어 항상 204를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "로그아웃 성공"),
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletRequest httpRequest
    ) {
        String accessToken = (String) httpRequest.getAttribute(JwtAuthenticationFilter.ACCESS_TOKEN_ATTRIBUTE);
        LogoutRequest request = new LogoutRequest(refreshToken, accessToken);
        logoutService.execute(request.toCommand());

        ResponseCookie expired = buildRefreshTokenCookie("", 0);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }

    private ResponseCookie buildRefreshTokenCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
