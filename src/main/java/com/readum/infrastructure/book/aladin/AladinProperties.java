package com.readum.infrastructure.book.aladin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "aladin")
public record AladinProperties(
        String ttbKey,
        String baseUrl,
        String searchTarget,
        String output,
        String version,
        String cover,
        Duration requestTimeout
) {
}
