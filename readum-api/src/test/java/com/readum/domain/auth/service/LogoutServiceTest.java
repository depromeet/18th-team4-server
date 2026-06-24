package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.LogoutCommand;
import com.readum.domain.auth.dto.LogoutResult;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.auth.out.TokenGenerator;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.model.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private TokenBlacklistStore tokenBlacklistStore;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private LogoutService logoutService;

    @Test
    void Refresh_Token과_Access_Token이_모두_유효하면_RT_폐기_후_AT를_블랙리스트에_등록한다() {
        String refreshToken = "refresh-token";
        String accessToken = "access-token";
        Long userId = 5L;
        String accessJwtId = "access-jwt-id";
        Instant accessExpiresAt = Instant.now().plus(Duration.ofMinutes(15));

        given(tokenGenerator.parse(refreshToken)).willReturn(
                new ParsedToken(userId, "USER", "refresh-jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );
        given(tokenGenerator.parse(accessToken)).willReturn(
                new ParsedToken(userId, "USER", accessJwtId, accessExpiresAt, TokenType.ACCESS)
        );

        LogoutResult result = logoutService.execute(new LogoutCommand(refreshToken, accessToken));

        InOrder order = inOrder(refreshTokenRepository, tokenBlacklistStore);
        order.verify(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        order.verify(tokenBlacklistStore).add(eq(accessJwtId), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void Refresh_Token만_유효하고_Access_Token이_없으면_RT_폐기만_수행된다() {
        String refreshToken = "refresh-token";
        Long userId = 5L;

        given(tokenGenerator.parse(refreshToken)).willReturn(
                new ParsedToken(userId, "USER", "refresh-jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );

        LogoutResult result = logoutService.execute(new LogoutCommand(refreshToken, null));

        verify(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));
        verify(tokenBlacklistStore, never()).add(anyString(), any());
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void Refresh_Token_쿠키가_없으면_멱등하게_anonymous_결과를_반환하고_아무것도_수행하지_않는다() {
        LogoutResult result = logoutService.execute(new LogoutCommand(null, null));

        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any(Instant.class));
        verify(tokenBlacklistStore, never()).add(anyString(), any());
        assertThat(result.userId()).isNull();
    }

    @Test
    void Refresh_Token이_유효하지_않으면_멱등하게_anonymous_결과를_반환한다() {
        String refreshToken = "invalid-refresh-token";
        given(tokenGenerator.parse(refreshToken))
                .willThrow(new UnauthorizedException(AuthErrorCode.INVALID_TOKEN));

        LogoutResult result = logoutService.execute(new LogoutCommand(refreshToken, null));

        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any(Instant.class));
        verify(tokenBlacklistStore, never()).add(anyString(), any());
        assertThat(result.userId()).isNull();
    }

    @Test
    void Refresh_Token_자리에_Access_Token이_들어오면_멱등하게_anonymous_결과를_반환한다() {
        String notRefreshToken = "access-token";
        given(tokenGenerator.parse(notRefreshToken)).willReturn(
                new ParsedToken(5L, "USER", "jwt-id", Instant.now().plus(Duration.ofMinutes(15)), TokenType.ACCESS)
        );

        LogoutResult result = logoutService.execute(new LogoutCommand(notRefreshToken, null));

        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any(Instant.class));
        verify(tokenBlacklistStore, never()).add(anyString(), any());
        assertThat(result.userId()).isNull();
    }

    @Test
    void Access_Token이_유효하지_않으면_블랙리스트_등록을_건너뛰고_RT_폐기는_수행된다() {
        String refreshToken = "refresh-token";
        String invalidAccessToken = "invalid-access-token";
        Long userId = 5L;

        given(tokenGenerator.parse(refreshToken)).willReturn(
                new ParsedToken(userId, "USER", "refresh-jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );
        given(tokenGenerator.parse(invalidAccessToken))
                .willThrow(new UnauthorizedException(AuthErrorCode.INVALID_TOKEN));

        LogoutResult result = logoutService.execute(new LogoutCommand(refreshToken, invalidAccessToken));

        verify(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));
        verify(tokenBlacklistStore, never()).add(anyString(), any());
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void Access_Token의_userId가_Refresh_Token과_다르면_블랙리스트_등록을_건너뛴다() {
        String refreshToken = "refresh-token";
        String accessToken = "access-token";
        Long refreshUserId = 5L;
        Long otherUserId = 9L;

        given(tokenGenerator.parse(refreshToken)).willReturn(
                new ParsedToken(refreshUserId, "USER", "refresh-jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );
        given(tokenGenerator.parse(accessToken)).willReturn(
                new ParsedToken(otherUserId, "USER", "access-jwt-id", Instant.now().plus(Duration.ofMinutes(15)), TokenType.ACCESS)
        );

        LogoutResult result = logoutService.execute(new LogoutCommand(refreshToken, accessToken));

        verify(refreshTokenRepository).revokeAllByUserId(eq(refreshUserId), any(Instant.class));
        verify(tokenBlacklistStore, never()).add(anyString(), any());
        assertThat(result.userId()).isEqualTo(refreshUserId);
    }

    @Test
    void 블랙리스트_등록이_실패해도_Logout_은_성공으로_처리된다() {
        String refreshToken = "refresh-token";
        String accessToken = "access-token";
        Long userId = 5L;

        given(tokenGenerator.parse(refreshToken)).willReturn(
                new ParsedToken(userId, "USER", "refresh-jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );
        given(tokenGenerator.parse(accessToken)).willReturn(
                new ParsedToken(userId, "USER", "access-jwt-id", Instant.now().plus(Duration.ofMinutes(15)), TokenType.ACCESS)
        );
        doThrow(new DataAccessResourceFailureException("simulated blacklist failure"))
                .when(tokenBlacklistStore).add(anyString(), any());

        LogoutResult result = logoutService.execute(new LogoutCommand(refreshToken, accessToken));

        verify(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void Refresh_Token_폐기가_실패하면_예외가_전파되고_블랙리스트는_호출되지_않는다() {
        String refreshToken = "refresh-token";
        String accessToken = "access-token";
        Long userId = 5L;

        given(tokenGenerator.parse(refreshToken)).willReturn(
                new ParsedToken(userId, "USER", "refresh-jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );
        doThrow(new DataAccessResourceFailureException("simulated RT store failure"))
                .when(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));

        assertThatThrownBy(() -> logoutService.execute(new LogoutCommand(refreshToken, accessToken)))
                .isInstanceOf(DataAccessException.class);

        verify(tokenBlacklistStore, never()).add(anyString(), any());
    }
}
