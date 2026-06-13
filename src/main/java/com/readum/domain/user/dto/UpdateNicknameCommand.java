package com.readum.domain.user.dto;

public record UpdateNicknameCommand(String sessionId, String nickname) {
}
