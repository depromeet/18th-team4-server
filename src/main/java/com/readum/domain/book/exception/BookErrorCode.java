package com.readum.domain.book.exception;

import com.readum.domain.exception.ErrorCode;

public enum BookErrorCode implements ErrorCode {

    ALADIN_SEARCH_FAILED("도서 검색 외부 서비스 호출에 실패했습니다.");

    private final String message;

    BookErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
