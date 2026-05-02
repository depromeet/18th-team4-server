package com.readum.domain.aiChat.dto;

public record HistoryMessage(Role role, String content) {

    public enum Role {
        USER, ASSISTANT
    }
}
