package com.readwith.presentation.common;

import com.readwith.domain.exception.BadRequestException;
import com.readwith.domain.exception.BusinessException;
import com.readwith.domain.exception.ConflictException;
import com.readwith.domain.exception.ForbiddenException;
import com.readwith.domain.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 도메인 비즈니스 예외 - 잘못된 요청
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<?>> handleBadRequest(BadRequestException ex) {
        log.warn("Bad request: {}", ex.getErrorCode().getMessage());
        return ApiResponse.error(HttpStatus.BAD_REQUEST, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 접근 권한 없음
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<?>> handleForbidden(ForbiddenException ex) {
        log.warn("Forbidden: {}", ex.getErrorCode().getMessage());
        return ApiResponse.error(HttpStatus.FORBIDDEN, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 리소스 미존재
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(NotFoundException ex) {
        log.warn("Not found: {}", ex.getErrorCode().getMessage());
        return ApiResponse.error(HttpStatus.NOT_FOUND, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 상태 충돌
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<?>> handleConflict(ConflictException ex) {
        log.warn("Conflict: {}", ex.getErrorCode().getMessage());
        return ApiResponse.error(HttpStatus.CONFLICT, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 미분류 (fallback)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException ex) {
        log.error("Unclassified business exception", ex);
        return ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    }

    // 잘못된 인자 전달 (도메인 서비스의 명시적 유효성 검사 실패 등)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ApiResponse.error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // @Valid 어노테이션 검증 실패 (필드 제약 조건 위반)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("유효하지 않은 요청입니다.");
        log.debug("Validation failed: {}", message);
        return ApiResponse.error(HttpStatus.BAD_REQUEST, message);
    }

    // @PathVariable, @RequestParam 타입 변환 실패 (예: long 파라미터에 문자열 전달)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("'%s'에 잘못된 값이 전달되었습니다: %s", ex.getName(), ex.getValue());
        log.debug("Type mismatch: {}", message);
        return ApiResponse.error(HttpStatus.BAD_REQUEST, message);
    }

    // 요청 바디 JSON 파싱 실패 (잘못된 형식 또는 타입 불일치)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.debug("Unreadable message: {}", ex.getMessage());
        return ApiResponse.error(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다.");
    }

    // 위에서 처리되지 않은 모든 예외 (예기치 않은 서버 오류)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    }
}
