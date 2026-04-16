package com.readwith.presentation.common;

import com.readwith.domain.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 도메인 비즈니스 규칙 위반 (ErrorCode에 정의된 상태코드와 메시지 사용)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException ex) {
        return ApiResponse.error(ex.getErrorCode().getStatus(), ex.getErrorCode().getMessage());
    }

    // 잘못된 인자 전달 (도메인 서비스의 명시적 유효성 검사 실패 등)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // @Valid 어노테이션 검증 실패 (필드 제약 조건 위반)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("유효하지 않은 요청입니다.");
        return ApiResponse.error(HttpStatus.BAD_REQUEST, message);
    }

    // @PathVariable, @RequestParam 타입 변환 실패 (예: long 파라미터에 문자열 전달)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("'%s'에 잘못된 값이 전달되었습니다: %s", ex.getName(), ex.getValue());
        return ApiResponse.error(HttpStatus.BAD_REQUEST, message);
    }

    // 요청 바디 JSON 파싱 실패 (잘못된 형식 또는 타입 불일치)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다.");
    }

    // 위에서 처리되지 않은 모든 예외 (예기치 않은 서버 오류)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception ex) {
        return ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    }
}
