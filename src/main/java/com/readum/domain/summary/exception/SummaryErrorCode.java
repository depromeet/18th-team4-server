package com.readum.domain.summary.exception;

import com.readum.domain.exception.ErrorCode;

public enum SummaryErrorCode implements ErrorCode {

    SUMMARY_NOT_YET_CREATED("아직 생성된 감상문이 없습니다."),
    SUMMARY_IN_PROGRESS("감상문을 생성 중입니다. 잠시 후 다시 시도해 주세요."),
    SUMMARY_GENERATION_FAILED("감상문 생성에 실패했습니다.");

    private final String message;

    SummaryErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
