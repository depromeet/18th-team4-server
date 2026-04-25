package com.readum.domain.book.exception;

import com.readum.domain.exception.ErrorCode;

public enum BookErrorCode implements ErrorCode {

    SEARCH_FAILED("도서 검색에 실패했습니다."),
    SEARCH_GATEWAY_ERROR("도서 검색 서비스에서 오류 응답을 받았습니다."),
    SEARCH_TIMEOUT("도서 검색 응답 시간이 초과되었습니다.");

    private final String message;

    BookErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
