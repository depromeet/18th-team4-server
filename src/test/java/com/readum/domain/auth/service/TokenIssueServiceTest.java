package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.dto.TokenIssueCommand;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenIssueServiceTest {

    @Mock
    private JwtTokenClient jwtTokenClient;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @InjectMocks
    private TokenIssueService tokenIssueService;

    @Test
    void 새로운_Token_Pair를_발급하고_Refresh_Token_메타데이터를_저장한다() {
        Long userId = 10L;
        String role = "USER";
        Duration accessTtl = Duration.ofMinutes(30);
        Duration refreshTtl = Duration.ofDays(14);

        given(jwtTokenClient.generateAccessToken(eq(userId), eq(role), anyString())).willReturn("access-token");
        given(jwtTokenClient.generateRefreshToken(any(RefreshTokenPayload.class))).willReturn("refresh-token");
        given(jwtTokenClient.accessTokenTtl()).willReturn(accessTtl);
        given(jwtTokenClient.refreshTokenTtl()).willReturn(refreshTtl);

        TokenPair pair = tokenIssueService.execute(new TokenIssueCommand(userId, role));

        assertThat(pair.accessToken()).isEqualTo("access-token");
        assertThat(pair.refreshToken()).isEqualTo("refresh-token");
        assertThat(pair.accessTokenTtl()).isEqualTo(accessTtl);
        assertThat(pair.refreshTokenTtl()).isEqualTo(refreshTtl);

        ArgumentCaptor<String> jwtIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> issuedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiresAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenStore).save(eq(userId), jwtIdCaptor.capture(), issuedAtCaptor.capture(), expiresAtCaptor.capture());

        assertThat(jwtIdCaptor.getValue()).isNotBlank();
        Duration savedTtl = Duration.between(issuedAtCaptor.getValue(), expiresAtCaptor.getValue());
        assertThat(savedTtl).isEqualTo(refreshTtl);
    }
}
