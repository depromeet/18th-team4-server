package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.LogoutCommand;
import com.readum.domain.auth.dto.LogoutResult;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private JwtTokenClient jwtTokenClient;

    @Mock
    private TokenBlacklistStore tokenBlacklistStore;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @InjectMocks
    private LogoutService logoutService;

    @Test
    void Refresh_Token을_먼저_폐기한_뒤_Access_Token을_블랙리스트에_등록한다() {
        String accessToken = "access-token";
        Long userId = 5L;
        String jwtId = "jwt-id";
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(15));

        given(jwtTokenClient.parse(accessToken)).willReturn(
                new ParsedToken(userId, "USER", jwtId, expiresAt, TokenType.ACCESS)
        );

        LogoutResult result = logoutService.execute(new LogoutCommand(accessToken));

        InOrder order = inOrder(refreshTokenStore, tokenBlacklistStore);
        order.verify(refreshTokenStore).revokeAll(userId);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        order.verify(tokenBlacklistStore).add(eq(jwtId), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void Refresh_Token_폐기가_실패하면_예외가_전파되고_블랙리스트는_호출되지_않는다() {
        String accessToken = "access-token";
        Long userId = 5L;
        String jwtId = "jwt-id";
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(15));

        given(jwtTokenClient.parse(accessToken)).willReturn(
                new ParsedToken(userId, "USER", jwtId, expiresAt, TokenType.ACCESS)
        );
        doThrow(new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE))
                .when(refreshTokenStore).revokeAll(userId);

        assertThatThrownBy(() -> logoutService.execute(new LogoutCommand(accessToken)))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(tokenBlacklistStore, never()).add(anyString(), any());
    }

    @Test
    void Access_Token이_아니면_INVALID_TOKEN_예외가_발생하고_아무것도_수행되지_않는다() {
        String refreshToken = "refresh-token";
        given(jwtTokenClient.parse(refreshToken)).willReturn(
                new ParsedToken(1L, null, "jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );

        assertThatThrownBy(() -> logoutService.execute(new LogoutCommand(refreshToken)))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        verify(tokenBlacklistStore, never()).add(anyString(), any());
        verify(refreshTokenStore, never()).revokeAll(anyLong());
    }
}
