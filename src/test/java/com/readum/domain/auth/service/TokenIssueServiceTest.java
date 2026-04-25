package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.dto.TokenIssueCommand;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.jwt.JwtTokenProvider;
import com.readum.model.auth.entity.RefreshToken;
import com.readum.model.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenIssueServiceTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private TokenIssueService tokenIssueService;

    @Test
    void 새로운_Token_Pair를_발급하고_Refresh_Token_메타데이터를_저장한다() {
        Long userId = 10L;
        String role = "USER";
        Duration accessTtl = Duration.ofMinutes(30);
        Duration refreshTtl = Duration.ofDays(14);

        given(jwtTokenProvider.generateAccessToken(eq(userId), eq(role), anyString())).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(any(RefreshTokenPayload.class))).willReturn("refresh-token");
        given(jwtTokenProvider.accessTokenTtl()).willReturn(accessTtl);
        given(jwtTokenProvider.refreshTokenTtl()).willReturn(refreshTtl);

        TokenPair pair = tokenIssueService.execute(new TokenIssueCommand(userId, role));

        assertThat(pair.accessToken()).isEqualTo("access-token");
        assertThat(pair.refreshToken()).isEqualTo("refresh-token");
        assertThat(pair.accessTokenTtl()).isEqualTo(accessTtl);
        assertThat(pair.refreshTokenTtl()).isEqualTo(refreshTtl);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).saveAndFlush(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getJwtId()).isNotBlank();
        assertThat(saved.getParentJwtId()).isNull();
        assertThat(Duration.between(saved.getIssuedAt(), saved.getExpiresAt())).isEqualTo(refreshTtl);
    }
}
