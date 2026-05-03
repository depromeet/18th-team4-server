# Exception Convention

CLAUDE.md 의 "Exception Convention" 섹션을 보충하는 상세 문서. 예외 계층 요약·do/don't 규칙은 CLAUDE.md 에 있고, 여기에는 **실제 작성 예시**와 **확장 절차**를 둔다.

## 예외 계층 구조

```
RuntimeException
 └─ BusinessException            (domain/exception/)
     ├─ BadRequestException      → 400
     ├─ UnauthorizedException    → 401
     ├─ ForbiddenException       → 403
     ├─ NotFoundException        → 404
     ├─ ConflictException        → 409
     └─ TooManyRequestsException → 429
```

- `BusinessException` 은 `ErrorCode` 하나만 필드로 들고 있음
- 서브클래스는 **HTTP 상태 매핑** 용도가 주 목적. 세부 분기 정보는 `ErrorCode` enum 값으로 표현
- 예외: `TooManyRequestsException` 은 선택적으로 `retryAfterSeconds` 필드를 추가로 가진다.
  retry 간격이 서비스마다 다를 수 있으므로 호출자(서비스/어댑터)가 직접 결정한다.
  값이 있으면 `GlobalExceptionHandler` 가 `Retry-After: N` 응답 헤더로 포함한다.

## ErrorCode enum 템플릿

도메인마다 하나씩 `domain/{feature}/exception/{Feature}ErrorCode.java` 에 배치.

```java
package com.readum.domain.auth.exception;

import com.readum.domain.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {

    INVALID_TOKEN("유효하지 않은 토큰입니다."),
    TOKEN_EXPIRED("토큰이 만료되었습니다."),
    TOKEN_REVOKED("로그아웃된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND("리프레시 토큰이 존재하지 않습니다."),
    REFRESH_TOKEN_EXPIRED("리프레시 토큰이 만료되었습니다."),
    REFRESH_TOKEN_REUSE_DETECTED("리프레시 토큰 재사용이 감지되었습니다.");

    private final String message;

    AuthErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
```

- enum 이름은 **상태 중심** 으로 명명 (`REFRESH_TOKEN_EXPIRED`, not `RT_ERR_01`)
- message 는 한글, API 응답 `error.message` 에 그대로 노출되므로 사용자에게 보여줄 수 있는 문구로
- 같은 도메인 내 에러만 한 enum 에 모은다. 도메인 간 재사용이 필요하면 새 enum 을 만들어 중복 정의

## 예외 던지는 위치와 방식

### 도메인 서비스 / 인프라 어댑터

```java
// O — 서브클래스 타입으로 HTTP 상태, ErrorCode 로 세부 분기
if (parsed.type() != TokenType.REFRESH) {
    throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
}
```

```java
// X — 비즈니스 경로에서 raw 예외 금지
throw new RuntimeException("refresh token invalid");
throw new IllegalArgumentException("token type mismatch");
```

### IllegalStateException 의 사용처

**프로그램 버그** (도달 불가 분기, 데이터 정합성 위반) 에만 fail-fast 용도로 사용. 사용자 요청 흐름에서 발생 가능한 에러에는 쓰지 않는다.

```java
// O — 데이터 정합성이 깨진 경우 (grace 상태인데 child 가 없음)
throw new IllegalStateException("grace state without child: " + oldJwtId);

// X — 사용자 입력이 잘못된 경우
throw new IllegalStateException("email format invalid");  // BadRequestException 으로
```

## GlobalExceptionHandler

- 위치: `presentation/common/GlobalExceptionHandler`
- 책임: `BusinessException` 하위 타입별로 HTTP 상태 매핑 + `ApiResponse.error(status, message)` 생성
- 개별 컨트롤러에 `@ExceptionHandler` 붙이지 않는다 — 매핑은 여기 한 곳에만

응답 형식:

```json
{
  "error": {
    "message": "리프레시 토큰이 만료되었습니다."
  }
}
```

## 예외 검증 테스트 패턴

`assertThatThrownBy` 로 잡을 때 **예외 타입 + ErrorCode 를 함께** 검증. 리플렉션 기반 문자열 키(`extracting("errorCode")`)는 필드명 변경 시 컴파일 타임에 안 잡히므로 금지.

```java
import org.assertj.core.api.InstanceOfAssertFactories;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Test
void REUSE_DETECTED_결과면_REFRESH_TOKEN_REUSE_DETECTED_예외가_발생한다() {
    given(refreshTokenStore.rotate(any())).willReturn(RotateResult.reuseDetected());

    assertThatThrownBy(() -> tokenRefreshService.execute(new TokenRefreshCommand(OLD_RT)))
            .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
            .extracting(UnauthorizedException::getErrorCode)
            .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
}
```

- `asInstanceOf(type(X.class))` 로 타입 검증 + 좁히기를 한 번에
- `extracting(X::getErrorCode)` 는 메서드 레퍼런스, IDE 가 리네이밍 추적

## 신규 도메인에 ErrorCode 추가

1. `domain/{feature}/exception/{Feature}ErrorCode.java` 생성
2. `implements ErrorCode` + 한글 메시지 enum 값 나열
3. 서비스/어댑터에서 `throw new {HttpStatus}Exception({Feature}ErrorCode.XXX)` 로 던짐
4. 테스트에서 위 assertion 패턴으로 검증

> `GlobalExceptionHandler` 는 대부분의 경우 **건드릴 필요 없다**. 기존 6개 HTTP 상태 서브클래스 안에서 끝난다.

## 신규 HTTP 상태 추가 (드문 경우)

기존 6개(400/401/403/404/409/429)로 표현 불가능한 상태가 필요할 때만:

1. `domain/exception/{Status}Exception.java` 추가 (`extends BusinessException`)
2. `GlobalExceptionHandler` 에 `@ExceptionHandler({Status}Exception.class)` 핸들러 추가
3. 해당 ErrorCode 추가
4. `CLAUDE.md` 의 "Exception Convention" 표에 행 추가

> 참고로 `TooManyRequestsException(429)` 는 외부 LLM rate limit 매핑을 위해 도입됐다 (`AiChatErrorCode.AI_RATE_LIMIT_EXCEEDED`). 503(Service Unavailable, DB 장애 외) 등이 다음 후보. 추가 전에 기존 분류로 표현 가능한지 먼저 검토.

### `TooManyRequestsException` 의 Retry-After 사용법

retry 간격을 알고 있는 서비스는 두 번째 생성자를 사용한다.

```java
// retry 간격을 알 때 — Retry-After: 60 헤더가 응답에 포함된다
throw new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_EXCEEDED, 60L);

// retry 간격을 모를 때 — Retry-After 헤더 없이 429만 반환
throw new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_EXCEEDED);
```

- retry 간격을 알 수 없는 경우(예: 외부 API 에러 메시지에서 파싱 불가)에는 단일 인자 생성자를 쓴다
- 적절한 대기 시간을 아는 경우(예: 외부 API 헤더 `Retry-After` 값)에는 이를 그대로 전달한다

## DB 계층 예외

- `DataAccessException` 는 `GlobalExceptionHandler` 에서 **503 Service Unavailable** 로 매핑 (이미 처리됨)
- JPA 제약 위반 등을 비즈니스 에러로 다시 던지고 싶을 때는 서비스 레이어에서 catch → `ConflictException` 등으로 rethrow
