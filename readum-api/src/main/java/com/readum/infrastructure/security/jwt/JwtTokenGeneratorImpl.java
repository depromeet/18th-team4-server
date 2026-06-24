package com.readum.infrastructure.security.jwt;

import com.readum.domain.auth.config.AuthProperties;
import com.readum.domain.auth.dto.ParsedToken;
import com.readum.domain.auth.dto.ParsedToken.TokenType;
import com.readum.domain.auth.dto.RefreshTokenPayload;
import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.auth.out.TokenGenerator;
import com.readum.domain.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenGeneratorImpl implements TokenGenerator {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYP = "typ";
    private static final String TYP_ACCESS = "access";
    private static final String TYP_REFRESH = "refresh";
    private static final int HS256_MIN_KEY_BYTES = 32;

    private final JwtProperties jwtProperties;
    private final AuthProperties authProperties;
    private SecretKey signingKey;

    public JwtTokenGeneratorImpl(JwtProperties jwtProperties, AuthProperties authProperties) {
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
    }

    @PostConstruct
    void init() {
        String secret = jwtProperties.secret();
        if (StringUtils.isBlank(secret)) {
            throw new IllegalStateException(
                    "jwt.secret 이 비어있습니다. 환경변수 JWT_SECRET 또는 application-{profile}.yml 의 jwt.secret 을 설정하세요."
            );
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "jwt.secret 이 유효한 Base64 문자열이 아닙니다. Base64 로 인코딩된 시크릿을 설정하세요.",
                    e
            );
        }
        if (decoded.length < HS256_MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 의 디코딩된 키 길이가 " + decoded.length + " 바이트로 너무 짧습니다. HS256 은 최소 "
                            + HS256_MIN_KEY_BYTES + " 바이트(256 비트) 키를 요구합니다."
            );
        }
        this.signingKey = new SecretKeySpec(decoded, "HmacSHA256");
    }

    @Override
    public String generateAccessToken(Long userId, String role, String jwtId) {
        Instant now = Instant.now();
        return buildToken(userId, role, jwtId, TYP_ACCESS, now, now.plus(authProperties.accessTokenTtl()));
    }

    @Override
    public String generateRefreshToken(RefreshTokenPayload payload) {
        return buildToken(
                payload.userId(),
                payload.role(),
                payload.jwtId(),
                TYP_REFRESH,
                payload.issuedAt(),
                payload.expiresAt()
        );
    }

    private String buildToken(Long userId, String role, String jwtId, String typ, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .issuer(jwtProperties.issuer())
                .subject(String.valueOf(userId))
                .id(jwtId)
                .claim(CLAIM_TYP, typ)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public ParsedToken parse(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(jwtProperties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(AuthErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
        }

        String typ = claims.get(CLAIM_TYP, String.class);
        TokenType type = switch (typ) {
            case TYP_ACCESS -> TokenType.ACCESS;
            case TYP_REFRESH -> TokenType.REFRESH;
            case null, default -> throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
        };

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
        }

        return new ParsedToken(
                userId,
                claims.get(CLAIM_ROLE, String.class),
                claims.getId(),
                claims.getExpiration().toInstant(),
                type
        );
    }
}
