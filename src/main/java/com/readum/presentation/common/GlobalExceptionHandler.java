package com.readum.presentation.common;

import com.readum.domain.exception.BadGatewayException;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.ExternalApiException;
import com.readum.domain.exception.ForbiddenException;
import com.readum.domain.exception.GatewayTimeoutException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.exception.UnprocessableEntityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 도메인 비즈니스 예외 - 잘못된 요청
    // 응답 메시지는 ex.getMessage() 를 쓴다. 단일 인자 생성 시에는 ErrorCode 기본 메시지와 동일하지만,
    // override 생성자로 만든 경우(예: 가드레일 거부 텍스트 = properties 정본) 그 값이 그대로 노출된다.
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleBadRequest(BadRequestException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return GlobalApiResponse.error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // 도메인 비즈니스 예외 - 인증 실패
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized: {}", ex.getErrorCode().getMessage());
        return GlobalApiResponse.error(HttpStatus.UNAUTHORIZED, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 접근 권한 없음
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleForbidden(ForbiddenException ex) {
        log.warn("Forbidden: {}", ex.getErrorCode().getMessage());
        return GlobalApiResponse.error(HttpStatus.FORBIDDEN, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 리소스 미존재
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleNotFound(NotFoundException ex) {
        log.warn("Not found: {}", ex.getErrorCode().getMessage());
        return GlobalApiResponse.error(HttpStatus.NOT_FOUND, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 처리 불가 엔티티 (422)
    @ExceptionHandler(UnprocessableEntityException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleUnprocessableEntity(UnprocessableEntityException ex) {
        log.warn("Unprocessable entity: {}", ex.getErrorCode().getMessage());
        return GlobalApiResponse.error(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 상태 충돌
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleConflict(ConflictException ex) {
        log.warn("Conflict: {}", ex.getErrorCode().getMessage());
        if (ex.getPayload() != null) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new GlobalApiResponse<>(ex.getPayload(), new GlobalApiResponse.ErrorBody(ex.getErrorCode().getMessage())));
        }
        return GlobalApiResponse.error(HttpStatus.CONFLICT, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 호출 한도 초과 (외부 LLM 등)
    // RateLimitInfo 가 동봉되어 있으면 X-RateLimit-* / Retry-After 헤더로 매핑한다.
    // SSE 경로의 mid-stream 429 는 이미 응답이 commit 되어 이 핸들러를 거치지 않고
    // SSE error event payload 로 같은 정보를 운반한다 (AiChatController 참조).
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleTooManyRequests(TooManyRequestsException ex) {
        log.warn("Too many requests: {} - rateLimitInfo={}",
                ex.getErrorCode().getMessage(), ex.getRateLimitInfo());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        RateLimitInfo info = ex.getRateLimitInfo();
        if (info != null) {
            info.toHttpHeaders().forEach(builder::header);
        }
        return builder.body(new GlobalApiResponse<>(
                null, new GlobalApiResponse.ErrorBody(ex.getErrorCode().getMessage())));
    }

    // 외부 시스템 응답 오류 (업스트림 5xx) → 502
    @ExceptionHandler(BadGatewayException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleBadGateway(BadGatewayException ex) {
        log.error("외부 시스템 오류 응답 - {}", ex.getErrorCode().name(), ex);
        return GlobalApiResponse.error(HttpStatus.BAD_GATEWAY, ex.getErrorCode().getMessage());
    }

    // 외부 시스템 응답 시간 초과 / IO 실패 → 504
    @ExceptionHandler(GatewayTimeoutException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleGatewayTimeout(GatewayTimeoutException ex) {
        log.error("외부 시스템 응답 지연 - {}", ex.getErrorCode().name(), ex);
        return GlobalApiResponse.error(HttpStatus.GATEWAY_TIMEOUT, ex.getErrorCode().getMessage());
    }

    // 외부 시스템 호출 미분류 실패 → 500
    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleExternalApi(ExternalApiException ex) {
        log.error("외부 시스템 호출 실패 - {}", ex.getErrorCode().name(), ex);
        return GlobalApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, ex.getErrorCode().getMessage());
    }

    // 도메인 비즈니스 예외 - 일시적 처리 불가 (외부 의존 장애 fail-closed 등) → 503 + Retry-After
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.error("Service unavailable: {}", ex.getErrorCode().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(new GlobalApiResponse<>(null, new GlobalApiResponse.ErrorBody(ex.getMessage())));
    }

    // DB 접근 실패 - 커넥션 끊김, 타임아웃, 제약 위반 등
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleDataAccess(DataAccessException ex) {
        log.error("DB 장애", ex);
        return GlobalApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE, ErrorMessages.SERVICE_UNAVAILABLE);
    }

    // 도메인 비즈니스 예외 - 미분류 (fallback)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleBusiness(BusinessException ex) {
        log.error("Unclassified business exception", ex);
        return GlobalApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    }

    // 잘못된 인자 전달 (도메인 서비스의 명시적 유효성 검사 실패 등)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return GlobalApiResponse.error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // @CookieValue(required = true) 로 선언된 쿠키가 요청에 없을 때 (user_session 등 인증 쿠키 → 401)
    @ExceptionHandler(MissingRequestCookieException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleMissingCookie(MissingRequestCookieException ex) {
        log.warn("Missing cookie: {}", ex.getCookieName());
        return GlobalApiResponse.error(HttpStatus.UNAUTHORIZED, "필수 쿠키가 없습니다: " + ex.getCookieName());
    }

    // @Valid 어노테이션 검증 실패 (필드 제약 조건 위반)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("유효하지 않은 요청입니다.");
        log.debug("Validation failed: {}", message);
        return GlobalApiResponse.error(HttpStatus.BAD_REQUEST, message);
    }

    // @PathVariable, @RequestParam 타입 변환 실패 (예: long 파라미터에 문자열 전달)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = String.format("'%s'에 잘못된 값이 전달되었습니다: %s", ex.getName(), ex.getValue());
        log.debug("Type mismatch: {}", message);
        return GlobalApiResponse.error(HttpStatus.BAD_REQUEST, message);
    }

    // 필수 쿼리 파라미터 누락 (?yearMonth= 미전달 등)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("Missing parameter: {}", ex.getParameterName());
        return GlobalApiResponse.error(HttpStatus.BAD_REQUEST, "필수 요청 값이 없습니다: " + ex.getParameterName());
    }

    // 요청 바디 JSON 파싱 실패 (잘못된 형식 또는 타입 불일치)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.debug("Unreadable message: {}", ex.getMessage());
        return GlobalApiResponse.error(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다.");
    }

    // AI API 인프라 오류 (Rate limit, 인증 키 오류 등) — 서버 설정/한도 문제
    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleNonTransientAi(NonTransientAiException ex) {
        log.error("Non-transient AI error: {}", ex.getMessage(), ex);
        return GlobalApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    }

    // AI API 일시적 오류 (타임아웃, 서버 오류 등) — 재시도로 해결 가능
    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<GlobalApiResponse<?>> handleTransientAi(TransientAiException ex) {
        log.error("Transient AI error (retryable): {}", ex.getMessage(), ex);
        return GlobalApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    }

    // 위에서 처리되지 않은 모든 예외 (예기치 않은 서버 오류)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<GlobalApiResponse<?>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return GlobalApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    }
}
