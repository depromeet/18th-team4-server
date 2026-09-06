package com.readum.domain.user.dto;

public record UpdateNicknameCommand(Long userId, String nickname) {
}
