package com.readum.domain.auth.dto;

public record TokenIssueCommand(
        Long userId,
        String role
) {
}
