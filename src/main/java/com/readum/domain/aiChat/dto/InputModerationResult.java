package com.readum.domain.aiChat.dto;

import java.util.List;

/**
 * 입력 moderation 판별 결과.
 * 상태를 3종으로 표현한다.
 * - PASSED      : 통과(LLM 호출 진행).
 * - BLOCKED     : 차단(거부 응답 + REJECTED 저장). flaggedCategories 에 차단을 유발한 카테고리.
 * - UNAVAILABLE : 외부 Moderation API 장애로 판별 불가(fail-closed). reason 에 원인 메모.
 */
public record InputModerationResult(Status status, List<String> flaggedCategories, String reason) {

    public enum Status {
        PASSED, BLOCKED, UNAVAILABLE
    }

    public InputModerationResult {
        flaggedCategories = flaggedCategories == null ? List.of() : List.copyOf(flaggedCategories);
    }

    public static InputModerationResult passed() {
        return new InputModerationResult(Status.PASSED, List.of(), null);
    }

    public static InputModerationResult blocked(List<String> flaggedCategories) {
        return new InputModerationResult(Status.BLOCKED, flaggedCategories, null);
    }

    public static InputModerationResult serviceUnavailable(String reason) {
        return new InputModerationResult(Status.UNAVAILABLE, List.of(), reason);
    }

    public boolean isPassed() {
        return status == Status.PASSED;
    }

    public boolean isBlocked() {
        return status == Status.BLOCKED;
    }

    public boolean isUnavailable() {
        return status == Status.UNAVAILABLE;
    }
}
