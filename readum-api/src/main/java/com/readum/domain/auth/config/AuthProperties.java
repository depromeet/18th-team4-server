package com.readum.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration refreshGracePeriod
) {
}
