package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.dto.RefreshTokenRotation;
import com.readum.domain.auth.dto.RotateResult;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.dto.TokenRefreshCommand;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenRefreshServiceTest {

    @Mock
    private JwtTokenClient jwtTokenClient;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @InjectMocks
    private TokenRefreshService tokenRefreshService;

    private static final Long USER_ID = 1L;
    private static final String OLD_RT = "old-refresh-token";
    private static final String OLD_JWT_ID = "old-jwt-id";
    private static final String NEW_ACCESS_TOKEN = "new-access";
    private static final String NEW_REFRESH_TOKEN = "new-refresh";
    private static final Duration RT_TTL = Duration.ofDays(14);
    private static final Duration AT_TTL = Duration.ofMinutes(30);
    private static final Duration GRACE_TTL = Duration.ofSeconds(3);

    private static final String ROLE = "USER";

    private ParsedToken refreshTokenClaims() {
        return new ParsedToken(USER_ID, ROLE, OLD_JWT_ID, Instant.now().plus(RT_TTL), TokenType.REFRESH);
    }

    @Test
    void ROTATED_결과면_새로운_Token_Pair를_발급한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(jwtTokenClient.refreshTokenTtl()).willReturn(RT_TTL);
        given(jwtTokenClient.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(jwtTokenClient.accessTokenTtl()).willReturn(AT_TTL);
        given(refreshTokenStore.rotate(any(RefreshTokenRotation.class))).willReturn(RotateResult.rotated());
        given(jwtTokenClient.generateAccessToken(eq(USER_ID), eq(ROLE), anyString())).willReturn(NEW_ACCESS_TOKEN);
        given(jwtTokenClient.generateRefreshToken(any(RefreshTokenPayload.class))).willReturn(NEW_REFRESH_TOKEN);

        TokenPair pair = tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT));

        assertThat(pair.accessToken()).isEqualTo(NEW_ACCESS_TOKEN);
        assertThat(pair.refreshToken()).isEqualTo(NEW_REFRESH_TOKEN);
        assertThat(pair.accessTokenTtl()).isEqualTo(AT_TTL);

        ArgumentCaptor<RefreshTokenRotation> rotationCaptor = ArgumentCaptor.forClass(RefreshTokenRotation.class);
        verify(refreshTokenStore).rotate(rotationCaptor.capture());
        RefreshTokenRotation captured = rotationCaptor.getValue();
        assertThat(captured.userId()).isEqualTo(USER_ID);
        assertThat(captured.oldJwtId()).isEqualTo(OLD_JWT_ID);
        assertThat(captured.gracePeriod()).isEqualTo(GRACE_TTL);
        assertThat(Duration.between(captured.newIssuedAt(), captured.newExpiresAt())).isEqualTo(RT_TTL);
    }

    @Test
    void GRACE_HIT_결과면_기존_successor_jti로_Refresh_JWT를_재서명한다() {
        String successorJwtId = "successor-jti";
        Instant successorIssuedAt = Instant.now().minusSeconds(1);
        Instant successorExpiresAt = successorIssuedAt.plus(RT_TTL);

        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(jwtTokenClient.refreshTokenTtl()).willReturn(RT_TTL);
        given(jwtTokenClient.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(jwtTokenClient.accessTokenTtl()).willReturn(AT_TTL);
        given(refreshTokenStore.rotate(any(RefreshTokenRotation.class)))
                .willReturn(RotateResult.graceHit(successorJwtId, successorIssuedAt, successorExpiresAt));
        RefreshTokenPayload expectedPayload = new RefreshTokenPayload(
                USER_ID, ROLE, successorJwtId, successorIssuedAt, successorExpiresAt
        );
        given(jwtTokenClient.generateAccessToken(eq(USER_ID), eq(ROLE), anyString())).willReturn(NEW_ACCESS_TOKEN);
        given(jwtTokenClient.generateRefreshToken(expectedPayload)).willReturn("grace-rt");

        TokenPair pair = tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT));

        assertThat(pair.accessToken()).isEqualTo(NEW_ACCESS_TOKEN);
        assertThat(pair.refreshToken()).isEqualTo("grace-rt");
        verify(jwtTokenClient).generateRefreshToken(expectedPayload);
    }

    @Test
    void NOT_FOUND_결과면_REFRESH_TOKEN_NOT_FOUND_예외가_발생한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(jwtTokenClient.refreshTokenTtl()).willReturn(RT_TTL);
        given(jwtTokenClient.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(refreshTokenStore.rotate(any(RefreshTokenRotation.class))).willReturn(RotateResult.notFound());

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }

    @Test
    void EXPIRED_결과면_REFRESH_TOKEN_EXPIRED_예외가_발생한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(jwtTokenClient.refreshTokenTtl()).willReturn(RT_TTL);
        given(jwtTokenClient.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(refreshTokenStore.rotate(any(RefreshTokenRotation.class))).willReturn(RotateResult.expired());

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
    }

    @Test
    void REUSE_DETECTED_결과면_REFRESH_TOKEN_REUSE_DETECTED_예외가_발생한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(jwtTokenClient.refreshTokenTtl()).willReturn(RT_TTL);
        given(jwtTokenClient.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(refreshTokenStore.rotate(any(RefreshTokenRotation.class))).willReturn(RotateResult.reuseDetected());

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
    }

    @Test
    void Refresh_Token이_아닌_Access_Token을_넘기면_INVALID_TOKEN_예외가_발생한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(
                new ParsedToken(USER_ID, ROLE, OLD_JWT_ID, Instant.now().plus(AT_TTL), TokenType.ACCESS)
        );

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
        verify(refreshTokenStore, never()).rotate(any(RefreshTokenRotation.class));
    }
}
