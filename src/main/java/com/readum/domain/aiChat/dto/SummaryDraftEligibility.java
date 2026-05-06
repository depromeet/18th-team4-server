package com.readum.domain.aiChat.dto;

import com.readum.domain.aiChat.exception.AiChatErrorCode;

public record SummaryDraftEligibility(boolean eligible, IneligibleReason reason) {

    public static SummaryDraftEligibility pass() {
        return new SummaryDraftEligibility(true, null);
    }

    public static SummaryDraftEligibility fail(IneligibleReason reason) {
        return new SummaryDraftEligibility(false, reason);
    }

    public enum IneligibleReason {
        SESSION_ALREADY_CLOSED(AiChatErrorCode.SESSION_ALREADY_CLOSED),
        SESSION_ALREADY_SUMMARIZING(AiChatErrorCode.SESSION_ALREADY_SUMMARIZING),
        CHAT_VOLUME_NOT_ENOUGH(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);

        private final AiChatErrorCode errorCode;

        IneligibleReason(AiChatErrorCode errorCode) {
            this.errorCode = errorCode;
        }

        public AiChatErrorCode getErrorCode() {
            return errorCode;
        }

        public String getMessage() {
            return errorCode.getMessage();
        }
    }
}
