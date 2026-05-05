package com.readum.infrastructure.security.jwt;

import com.readum.domain.auth.config.AuthProperties;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenGeneratorImplTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes());
    private static final String ISSUER = "readum-test";

    private JwtTokenGeneratorImpl tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = newGenerator(Duration.ofMinutes(30), Duration.ofDays(14));
    }

    private JwtTokenGeneratorImpl newGenerator(Duration accessTtl, Duration refreshTtl) {
        JwtProperties jwtProperties = new JwtProperties(SECRET, ISSUER, 10_000L);
        AuthProperties authProperties = new AuthProperties(accessTtl, refreshTtl, Duration.ofSeconds(3));
        JwtTokenGeneratorImpl generator = new JwtTokenGeneratorImpl(jwtProperties, authProperties);
        generator.init();
        return generator;
    }

    @Test
    void Access_Token을_생성하고_파싱하면_동일한_클레임이_복원된다() {
        String token = tokenGenerator.generateAccessToken(1L, "USER", "jwt-id-1");

        ParsedToken parsed = tokenGenerator.parse(token);

        assertThat(parsed.userId()).isEqualTo(1L);
        assertThat(parsed.role()).isEqualTo("USER");
        assertThat(parsed.jwtId()).isEqualTo("jwt-id-1");
        assertThat(parsed.type()).isEqualTo(TokenType.ACCESS);
    }

    @Test
    void Refresh_Token도_role_claim을_포함하여_REFRESH_타입으로_파싱된다() {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(Duration.ofDays(14));
        String token = tokenGenerator.generateRefreshToken(new RefreshTokenPayload(
                2L, "ADMIN", "jwt-id-2", issuedAt, expiresAt
        ));

        ParsedToken parsed = tokenGenerator.parse(token);

        assertThat(parsed.userId()).isEqualTo(2L);
        assertThat(parsed.role()).isEqualTo("ADMIN");
        assertThat(parsed.jwtId()).isEqualTo("jwt-id-2");
        assertThat(parsed.type()).isEqualTo(TokenType.REFRESH);
    }

    @Test
    void 만료된_토큰을_파싱하면_TOKEN_EXPIRED_예외가_발생한다() {
        JwtTokenGeneratorImpl expiredGenerator = newGenerator(Duration.ofSeconds(-1), Duration.ofDays(14));
        String token = expiredGenerator.generateAccessToken(1L, "USER", "jwt-id");

        assertThatThrownBy(() -> expiredGenerator.parse(token))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 서명이_변조된_토큰을_파싱하면_INVALID_TOKEN_예외가_발생한다() {
        String token = tokenGenerator.generateAccessToken(1L, "USER", "jwt-id");
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> tokenGenerator.parse(tampered))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
    }

    @Test
    void 파싱_불가능한_문자열을_넘기면_INVALID_TOKEN_예외가_발생한다() {
        assertThatThrownBy(() -> tokenGenerator.parse("not-a-jwt"))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
    }
}
