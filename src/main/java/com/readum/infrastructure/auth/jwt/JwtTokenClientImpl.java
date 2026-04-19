package com.readum.infrastructure.auth.jwt;

import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.out.JwtTokenClient;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.infrastructure.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenClientImpl implements JwtTokenClient {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYP = "typ";
    private static final String TYP_ACCESS = "access";
    private static final String TYP_REFRESH = "refresh";

    private final JwtProperties properties;
    private SecretKey signingKey;

    public JwtTokenClientImpl(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 이 비어있습니다. 환경변수 JWT_SECRET 또는 application-{profile}.yml 의 jwt.secret 을 설정하세요."
            );
        }
        byte[] decoded = Base64.getDecoder().decode(secret);
        this.signingKey = new SecretKeySpec(decoded, "HmacSHA256");
    }

    @Override
    public String generateAccessToken(Long userId, String role, String jwtId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .id(jwtId)
                .claim(CLAIM_TYP, TYP_ACCESS)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public String generateRefreshToken(Long userId, String role, String jwtId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .id(jwtId)
                .claim(CLAIM_TYP, TYP_REFRESH)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.refreshTokenTtl())))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public ParsedToken parse(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        String typ = claims.get(CLAIM_TYP, String.class);
        TokenType type = switch (typ) {
            case TYP_ACCESS -> TokenType.ACCESS;
            case TYP_REFRESH -> TokenType.REFRESH;
            case null, default -> throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        };

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        return new ParsedToken(
                userId,
                claims.get(CLAIM_ROLE, String.class),
                claims.getId(),
                claims.getExpiration().toInstant(),
                type
        );
    }

    @Override
    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    @Override
    public Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }

    @Override
    public Duration refreshGracePeriod() {
        return properties.refreshGracePeriod();
    }
}
