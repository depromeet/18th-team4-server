package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.dto.TokenRefreshCommand;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
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

    private ParsedToken refreshTokenClaims() {
        return new ParsedToken(USER_ID, null, OLD_JWT_ID, Instant.now().plus(RT_TTL), TokenType.REFRESH);
    }

    @Test
    void 저장된_Refresh_Token과_일치하면_새로운_Token_Pair를_발급한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(refreshTokenStore.findCurrent(USER_ID)).willReturn(Optional.of(OLD_RT));
        given(jwtTokenClient.generateAccessToken(eq(USER_ID), any(), anyString())).willReturn(NEW_ACCESS_TOKEN);
        given(jwtTokenClient.generateRefreshToken(eq(USER_ID), anyString())).willReturn(NEW_REFRESH_TOKEN);
        given(jwtTokenClient.accessTokenTtl()).willReturn(AT_TTL);
        given(jwtTokenClient.refreshTokenTtl()).willReturn(RT_TTL);
        given(jwtTokenClient.refreshGracePeriod()).willReturn(GRACE_TTL);

        TokenPair pair = tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT));

        assertThat(pair.accessToken()).isEqualTo(NEW_ACCESS_TOKEN);
        assertThat(pair.refreshToken()).isEqualTo(NEW_REFRESH_TOKEN);
        verify(refreshTokenStore).rotate(USER_ID, OLD_JWT_ID, NEW_REFRESH_TOKEN, RT_TTL, GRACE_TTL);
    }

    @Test
    void Refresh_Token_저장소가_비어있으면_REFRESH_TOKEN_NOT_FOUND_예외가_발생한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(refreshTokenStore.findCurrent(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        verify(refreshTokenStore, never()).rotate(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void 저장된_Refresh_Token과_다르지만_유예_기간_내면_정상_발급된다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(refreshTokenStore.findCurrent(USER_ID)).willReturn(Optional.of("different-rt"));
        given(refreshTokenStore.existsInGrace(USER_ID, OLD_JWT_ID)).willReturn(true);
        given(jwtTokenClient.generateAccessToken(eq(USER_ID), any(), anyString())).willReturn(NEW_ACCESS_TOKEN);
        given(jwtTokenClient.generateRefreshToken(eq(USER_ID), anyString())).willReturn(NEW_REFRESH_TOKEN);
        given(jwtTokenClient.accessTokenTtl()).willReturn(AT_TTL);
        given(jwtTokenClient.refreshTokenTtl()).willReturn(RT_TTL);
        given(jwtTokenClient.refreshGracePeriod()).willReturn(GRACE_TTL);

        TokenPair pair = tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT));

        assertThat(pair.refreshToken()).isEqualTo(NEW_REFRESH_TOKEN);
    }

    @Test
    void 저장된_Refresh_Token과_다르고_유예도_없으면_재사용_감지되어_전체_토큰이_폐기된다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(refreshTokenClaims());
        given(refreshTokenStore.findCurrent(USER_ID)).willReturn(Optional.of("different-rt"));
        given(refreshTokenStore.existsInGrace(USER_ID, OLD_JWT_ID)).willReturn(false);

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        verify(refreshTokenStore).deleteAll(USER_ID);
        verify(refreshTokenStore, never()).rotate(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void Refresh_Token이_아닌_Access_Token을_넘기면_INVALID_TOKEN_예외가_발생한다() {
        given(jwtTokenClient.parse(OLD_RT)).willReturn(
                new ParsedToken(USER_ID, "USER", OLD_JWT_ID, Instant.now().plus(AT_TTL), TokenType.ACCESS)
        );

        assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }
}
