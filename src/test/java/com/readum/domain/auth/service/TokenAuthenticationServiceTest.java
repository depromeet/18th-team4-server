package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.AuthenticatedPrincipal;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TokenAuthenticationServiceTest {

    @Mock
    private JwtTokenClient jwtTokenClient;

    @Mock
    private TokenBlacklistStore tokenBlacklistStore;

    @InjectMocks
    private TokenAuthenticationService tokenAuthenticationService;

    @Test
    void 유효한_Access_Token이면_AuthenticatedPrincipal을_반환한다() {
        String token = "access-token";
        String jwtId = "jwt-id";
        given(jwtTokenClient.parse(token)).willReturn(
                new ParsedToken(1L, "USER", jwtId, Instant.now().plus(Duration.ofMinutes(30)), TokenType.ACCESS)
        );
        given(tokenBlacklistStore.contains(jwtId)).willReturn(false);

        AuthenticatedPrincipal principal = tokenAuthenticationService.authenticate(token);

        assertThat(principal.userId()).isEqualTo(1L);
        assertThat(principal.role()).isEqualTo("USER");
    }

    @Test
    void Access_Token이_아니면_INVALID_TOKEN_예외가_발생한다() {
        String token = "refresh-token";
        given(jwtTokenClient.parse(token)).willReturn(
                new ParsedToken(1L, null, "jwt-id", Instant.now().plus(Duration.ofDays(14)), TokenType.REFRESH)
        );

        assertThatThrownBy(() -> tokenAuthenticationService.authenticate(token))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
    }

    @Test
    void 블랙리스트에_등록된_Access_Token이면_TOKEN_REVOKED_예외가_발생한다() {
        String token = "access-token";
        String jwtId = "jwt-id";
        given(jwtTokenClient.parse(token)).willReturn(
                new ParsedToken(1L, "USER", jwtId, Instant.now().plus(Duration.ofMinutes(30)), TokenType.ACCESS)
        );
        given(tokenBlacklistStore.contains(jwtId)).willReturn(true);

        assertThatThrownBy(() -> tokenAuthenticationService.authenticate(token))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.TOKEN_REVOKED);
    }
}
