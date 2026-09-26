package com.readum.domain.auth.dto;

public record AuthenticatedPrincipal(
        Long userId,
        String role
) {
}
