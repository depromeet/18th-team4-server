package com.readum.domain.auth.service;

import com.readum.domain.auth.dto.TokenIssueCommand;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.auth.out.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenIssueService {

    private final JwtTokenClient jwtTokenClient;
    private final RefreshTokenStore refreshTokenStore;

    public TokenPair execute(TokenIssueCommand command) {
        String accessJwtId = UUID.randomUUID().toString();
        String refreshJwtId = UUID.randomUUID().toString();

        String accessToken = jwtTokenClient.generateAccessToken(command.userId(), command.role(), accessJwtId);
        String refreshToken = jwtTokenClient.generateRefreshToken(command.userId(), command.role(), refreshJwtId);

        refreshTokenStore.save(command.userId(), refreshToken, jwtTokenClient.refreshTokenTtl());

        log.info("Token Pair 발급 완료 userId={}", command.userId());
        return new TokenPair(
                accessToken,
                refreshToken,
                jwtTokenClient.accessTokenTtl(),
                jwtTokenClient.refreshTokenTtl()
        );
    }
}
