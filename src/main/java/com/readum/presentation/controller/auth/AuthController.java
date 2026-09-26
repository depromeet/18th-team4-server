package com.readum.presentation.controller.auth;

import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.service.LogoutService;
import com.readum.domain.auth.service.TokenRefreshService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.common.security.JwtAuthenticationFilter;
import com.readum.presentation.controller.auth.dto.LogoutRequest;
import com.readum.presentation.controller.auth.dto.TokenRefreshRequest;
import com.readum.presentation.controller.auth.dto.TokenRefreshResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "Access Token 재발급 및 로그아웃")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final TokenRefreshService tokenRefreshService;
    private final LogoutService logoutService;
    private final boolean refreshCookieSecure;

    public AuthController(
            TokenRefreshService tokenRefreshService,
            LogoutService logoutService,
            @Value("${jwt.refresh-cookie-secure:true}") boolean refreshCookieSecure
    ) {
        this.tokenRefreshService = tokenRefreshService;
        this.logoutService = logoutService;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @Operation(
            summary = "Access Token 재발급",
            description = "쿠키의 Refresh Token 으로 새 Access/Refresh Token Pair 를 발급한다. " +
                    "재발급된 Refresh Token 은 HttpOnly 쿠키로 다시 내려준다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재발급 성공"),
            @ApiResponse(responseCode = "401", description = "Refresh Token 누락/만료/무효 또는 재사용 감지")
    })
    @PostMapping("/refresh")
    public ResponseEntity<GlobalApiResponse<TokenRefreshResponse>> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE) String refreshToken) {
        TokenRefreshRequest request = new TokenRefreshRequest(refreshToken);
        TokenPair pair = tokenRefreshService.execute(request.toCommand());

        ResponseCookie cookie = buildRefreshTokenCookie(pair.refreshToken(), pair.refreshTokenTtl().toSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new GlobalApiResponse<>(TokenRefreshResponse.from(pair), null));
    }

    @Operation(
            summary = "로그아웃",
            description = "Access Token 을 블랙리스트에 등록하고 Refresh Token 을 무효화한다. " +
                    "Refresh Token 쿠키도 즉시 만료시킨다 (idempotent — 토큰 없어도 204)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "로그아웃 성공 (응답 본문 없음)")
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
