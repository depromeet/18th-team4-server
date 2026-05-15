package com.readum.domain.auth.service;

import com.readum.domain.auth.config.AuthProperties;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.dto.TokenRefreshCommand;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.auth.out.TokenGenerator;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.model.auth.entity.RefreshToken;
import com.readum.model.auth.entity.RefreshTokenFixture;
import com.readum.model.auth.repository.RefreshTokenRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenRefreshServiceTest {

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private AuthProperties authProperties;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private TokenRefreshService tokenRefreshService;

    private static final Long USER_ID = 1L;
    private static final String OLD_RT = "old-refresh-token";
    private static final String OLD_JWT_ID = "old-jwt-id";
    private static final String NEW_ACCESS_TOKEN = "new-access";
    private static final String NEW_REFRESH_TOKEN = "new-refresh";
    private static final String GRACE_REFRESH_TOKEN = "grace-rt";
    private static final Duration RT_TTL = Duration.ofDays(14);
    private static final Duration AT_TTL = Duration.ofMinutes(30);
    private static final Duration GRACE_TTL = Duration.ofSeconds(3);
    private static final String ROLE = "USER";

    private ParsedToken refreshTokenClaims() {
        return new ParsedToken(USER_ID, ROLE, OLD_JWT_ID, Instant.now().plus(RT_TTL), TokenType.REFRESH);
    }

    private RefreshToken activeRow() {
        Instant now = Instant.now();
        return RefreshTokenFixture.of(
                100L, USER_ID, OLD_JWT_ID, null,
                now.minusSeconds(60), now.plus(RT_TTL),
                null, null, null,
                LocalDateTime.now()
        );
    }

    private RefreshToken revokedRow() {
        Instant now = Instant.now();
        return RefreshTokenFixture.of(
                100L, USER_ID, OLD_JWT_ID, null,
                now.minusSeconds(3600), now.plus(RT_TTL),
                null, null, now.minusSeconds(30),
                LocalDateTime.now()
        );
    }

    private RefreshToken expiredRow() {
        Instant now = Instant.now();
        return RefreshTokenFixture.of(
                100L, USER_ID, OLD_JWT_ID, null,
                now.minusSeconds(3600), now.minusSeconds(10),
                null, null, null,
                LocalDateTime.now()
        );
    }

    private RefreshToken inGraceRow() {
        Instant now = Instant.now();
        return RefreshTokenFixture.of(
                100L, USER_ID, OLD_JWT_ID, null,
                now.minusSeconds(120), now.plus(RT_TTL),
                now.minusSeconds(1), now.plusSeconds(2), null,
                LocalDateTime.now()
        );
    }

    private RefreshToken postGraceRow() {
        Instant now = Instant.now();
        return RefreshTokenFixture.of(
                100L, USER_ID, OLD_JWT_ID, null,
                now.minusSeconds(120), now.plus(RT_TTL),
                now.minusSeconds(60), now.minusSeconds(30), null,
                LocalDateTime.now()
        );
    }

    @Test
    void ACTIVE_row_를_rotate_하면_새로운_Token_Pair_를_발급한다() {
        given(tokenGenerator.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(authProperties.refreshTokenTtl()).willReturn(RT_TTL);
        given(authProperties.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(authProperties.accessTokenTtl()).willReturn(AT_TTL);
        given(refreshTokenRepository.findByUserIdAndJwtId(USER_ID, OLD_JWT_ID))
                .willReturn(Optional.of(activeRow()));
        given(refreshTokenRepository.rotate(eq(100L), any(Instant.class), any(Instant.class))).willReturn(1);
        given(tokenGenerator.generateAccessToken(eq(USER_ID), eq(ROLE), anyString())).willReturn(NEW_ACCESS_TOKEN);
        given(tokenGenerator.generateRefreshToken(any(RefreshTokenPayload.class))).willReturn(NEW_REFRESH_TOKEN);

        TokenPair pair = tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT));

        assertThat(pair.accessToken()).isEqualTo(NEW_ACCESS_TOKEN);
        assertThat(pair.refreshToken()).isEqualTo(NEW_REFRESH_TOKEN);
        assertThat(pair.accessTokenTtl()).isEqualTo(AT_TTL);

        ArgumentCaptor<RefreshToken> childCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).saveAndFlush(childCaptor.capture());
        RefreshToken child = childCaptor.getValue();
        assertThat(child.getUserId()).isEqualTo(USER_ID);
        assertThat(child.getParentJwtId()).isEqualTo(OLD_JWT_ID);
        assertThat(child.getJwtId()).isNotBlank();
    }

    @Test
    void IN_GRACE_row_는_기존_child_jti_로_Refresh_JWT_를_재서명한다() {
        String childJwtId = "child-jti";
        Instant childIssuedAt = Instant.now().minusSeconds(1);
        Instant childExpiresAt = childIssuedAt.plus(RT_TTL);
        RefreshToken child = RefreshTokenFixture.of(
                200L, USER_ID, childJwtId, OLD_JWT_ID,
                childIssuedAt, childExpiresAt,
                null, null, null,
                LocalDateTime.now()
        );

        given(tokenGenerator.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(authProperties.refreshTokenTtl()).willReturn(RT_TTL);
        given(authProperties.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(authProperties.accessTokenTtl()).willReturn(AT_TTL);
        given(refreshTokenRepository.findByUserIdAndJwtId(USER_ID, OLD_JWT_ID))
                .willReturn(Optional.of(inGraceRow()));
        given(refreshTokenRepository.findByParentJwtId(OLD_JWT_ID)).willReturn(Optional.of(child));

        RefreshTokenPayload expectedPayload = new RefreshTokenPayload(
                USER_ID, ROLE, childJwtId, childIssuedAt, childExpiresAt
        );
        given(tokenGenerator.generateAccessToken(eq(USER_ID), eq(ROLE), anyString())).willReturn(NEW_ACCESS_TOKEN);
        given(tokenGenerator.generateRefreshToken(expectedPayload)).willReturn(GRACE_REFRESH_TOKEN);

        TokenPair pair = tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT));

        assertThat(pair.accessToken()).isEqualTo(NEW_ACCESS_TOKEN);
        assertThat(pair.refreshToken()).isEqualTo(GRACE_REFRESH_TOKEN);
        verify(tokenGenerator).generateRefreshToken(expectedPayload);
        verify(refreshTokenRepository, never()).saveAndFlush(any(RefreshToken.class));
    }

    @Test
    void 저장소에_row_가_없으면_REFRESH_TOKEN_NOT_FOUND_예외가_발생한다() {
        given(tokenGenerator.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(authProperties.refreshTokenTtl()).willReturn(RT_TTL);
        given(authProperties.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(refreshTokenRepository.findByUserIdAndJwtId(USER_ID, OLD_JWT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
    }

    @Test
    void EXPIRED_row_면_REFRESH_TOKEN_EXPIRED_예외가_발생한다() {
        given(tokenGenerator.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(authProperties.refreshTokenTtl()).willReturn(RT_TTL);
        given(authProperties.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(refreshTokenRepository.findByUserIdAndJwtId(USER_ID, OLD_JWT_ID))
                .willReturn(Optional.of(expiredRow()));

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any(Instant.class));
    }

    @Test
    void REVOKED_row_면_REFRESH_TOKEN_REUSE_DETECTED_예외가_발생한다() {
        given(tokenGenerator.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(authProperties.refreshTokenTtl()).willReturn(RT_TTL);
        given(authProperties.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(refreshTokenRepository.findByUserIdAndJwtId(USER_ID, OLD_JWT_ID))
                .willReturn(Optional.of(revokedRow()));

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
    }

    @Test
    void POST_GRACE_row_면_userId_전체를_폐기하고_REUSE_DETECTED_예외가_발생한다() {
        given(tokenGenerator.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(authProperties.refreshTokenTtl()).willReturn(RT_TTL);
        given(authProperties.refreshGracePeriod()).willReturn(GRACE_TTL);
        given(refreshTokenRepository.findByUserIdAndJwtId(USER_ID, OLD_JWT_ID))
                .willReturn(Optional.of(postGraceRow()));

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        verify(refreshTokenRepository).revokeAllByUserId(eq(USER_ID), any(Instant.class));
    }

    @Test
    void Refresh_Token이_아닌_Access_Token을_넘기면_INVALID_TOKEN_예외가_발생한다() {
        given(tokenGenerator.parse(OLD_RT)).willReturn(
                new ParsedToken(USER_ID, ROLE, OLD_JWT_ID, Instant.now().plus(AT_TTL), TokenType.ACCESS)
        );

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
        verify(refreshTokenRepository, never()).findByUserIdAndJwtId(anyLong(), anyString());
    }
}
