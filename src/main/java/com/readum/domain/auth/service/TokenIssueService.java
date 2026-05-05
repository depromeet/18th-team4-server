package com.readum.domain.auth.service;

import com.readum.domain.auth.config.AuthProperties;
import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.dto.TokenIssueCommand;
import com.readum.domain.auth.dto.TokenPair;
import com.readum.domain.auth.out.TokenGenerator;
import com.readum.model.auth.entity.RefreshToken;
import com.readum.model.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenIssueService {

    private final TokenGenerator tokenGenerator;
    private final AuthProperties authProperties;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public TokenPair execute(TokenIssueCommand command) {
        String accessJwtId = UUID.randomUUID().toString();
        String refreshJwtId = UUID.randomUUID().toString();

        Instant now = Instant.now();
        Instant refreshExpiresAt = now.plus(authProperties.refreshTokenTtl());

        String accessToken = tokenGenerator.generateAccessToken(command.userId(), command.role(), accessJwtId);
        String refreshToken = tokenGenerator.generateRefreshToken(new RefreshTokenPayload(
                command.userId(), command.role(), refreshJwtId, now, refreshExpiresAt
        ));

        refreshTokenRepository.saveAndFlush(
                RefreshToken.create(command.userId(), refreshJwtId, now, refreshExpiresAt)
        );

        log.info("Token Pair 발급 완료 userId={}", command.userId());
        return new TokenPair(
                accessToken,
                refreshToken,
                authProperties.accessTokenTtl(),
                authProperties.refreshTokenTtl()
        );
    }
}
