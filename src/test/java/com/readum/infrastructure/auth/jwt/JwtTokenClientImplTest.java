package com.readum.infrastructure.auth.jwt;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.infrastructure.auth.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenClientImplTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes());
    private static final String ISSUER = "readum-test";

    private JwtTokenClientImpl client;

    @BeforeEach
    void setUp() {
        client = newClient(Duration.ofMinutes(30), Duration.ofDays(14));
    }

    private JwtTokenClientImpl newClient(Duration accessTtl, Duration refreshTtl) {
        JwtProperties properties = new JwtProperties(
                SECRET,
                ISSUER,
                accessTtl,
                refreshTtl,
                Duration.ofSeconds(3)
        );
        JwtTokenClientImpl c = new JwtTokenClientImpl(properties);
        c.init();
        return c;
    }

    @Test
    void Access_Token을_생성하고_파싱하면_동일한_클레임이_복원된다() {
        String token = client.generateAccessToken(1L, "USER", "jwt-id-1");

        ParsedToken parsed = client.parse(token);

        assertThat(parsed.userId()).isEqualTo(1L);
        assertThat(parsed.role()).isEqualTo("USER");
        assertThat(parsed.jwtId()).isEqualTo("jwt-id-1");
        assertThat(parsed.type()).isEqualTo(TokenType.ACCESS);
    }

    @Test
    void Refresh_Token도_role_claim을_포함하여_REFRESH_타입으로_파싱된다() {
        String token = client.generateRefreshToken(2L, "ADMIN", "jwt-id-2");

        ParsedToken parsed = client.parse(token);

        assertThat(parsed.userId()).isEqualTo(2L);
        assertThat(parsed.role()).isEqualTo("ADMIN");
        assertThat(parsed.jwtId()).isEqualTo("jwt-id-2");
        assertThat(parsed.type()).isEqualTo(TokenType.REFRESH);
    }

    @Test
    void 만료된_토큰을_파싱하면_TOKEN_EXPIRED_예외가_발생한다() {
        JwtTokenClientImpl expiredClient = newClient(Duration.ofSeconds(-1), Duration.ofDays(14));
        String token = expiredClient.generateAccessToken(1L, "USER", "jwt-id");

        assertThatThrownBy(() -> expiredClient.parse(token))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 서명이_변조된_토큰을_파싱하면_INVALID_TOKEN_예외가_발생한다() {
        String token = client.generateAccessToken(1L, "USER", "jwt-id");
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> client.parse(tampered))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void 파싱_불가능한_문자열을_넘기면_INVALID_TOKEN_예외가_발생한다() {
        assertThatThrownBy(() -> client.parse("not-a-jwt"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }
}
