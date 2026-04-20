package com.readum.presentation.controller.auth;

import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.service.LogoutService;
import com.readum.domain.auth.service.TokenRefreshService;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.common.security.JwtAuthenticationFilter;
import com.readum.presentation.controller.auth.dto.LogoutRequest;
import com.readum.presentation.controller.auth.dto.TokenRefreshRequest;
import com.readum.presentation.controller.auth.dto.TokenRefreshResponse;
import jakarta.servlet.http.HttpServletRequest;
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

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        TokenRefreshRequest request = new TokenRefreshRequest(refreshToken);
        TokenPair pair = tokenRefreshService.execute(request.toCommand());

        ResponseCookie cookie = buildRefreshTokenCookie(pair.refreshToken(), pair.refreshTokenTtl().toSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new ApiResponse<>(TokenRefreshResponse.from(pair), null));
    }

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
