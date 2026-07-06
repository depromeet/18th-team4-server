# Exception Convention

예외 처리 규칙의 원본(canonical) 문서. CLAUDE.md 에는 링크만 둔다.

## 예외 계층 구조

```text
RuntimeException
 └─ BusinessException                (domain/exception/)
     ├─ BadRequestException          → 400
     ├─ UnauthorizedException        → 401
     ├─ ForbiddenException           → 403
     ├─ NotFoundException            → 404
     ├─ ConflictException            → 409
     ├─ UnprocessableEntityException → 422
     ├─ TooManyRequestsException     → 429
     ├─ ServiceUnavailableException  → 503 (+ Retry-After 헤더)
     └─ ExternalApiException         → 500 (외부 API 실패의 공통 부모)
         ├─ BadGatewayException      → 502
         └─ GatewayTimeoutException  → 504
```

(HTTP 상태는 `GlobalExceptionHandler` 의 각 핸들러에서 확인한 현행 매핑. `BadGateway`/`GatewayTimeout` 은 `BusinessException` 직속이 아니라 `ExternalApiException` 을 상속한다.)

각 서브클래스의 용도 — 실제 사용처 기준:

| Exception | HTTP | 용도 | 실제 사용처 |
|-----------|:----:|------|------------|
| `BadRequestException` | 400 | 검증 실패, 비즈니스 규칙 위반 | 전 도메인 |
| `UnauthorizedException` | 401 | 인증 실패 (토큰 무효/만료) | auth |
| `ForbiddenException` | 403 | 인증됐지만 권한 없음 | 소유권 검증 |
| `NotFoundException` | 404 | 리소스 미존재 | 전 도메인 |
| `ConflictException` | 409 | 상태 충돌 (중복, 동시성) | 전 도메인 |
| `UnprocessableEntityException` | 422 | 요청 형식은 유효하나 도메인 상태가 처리 조건 미달 | `SummaryDraftPolicy` — 대화량 부족(`CHAT_VOLUME_NOT_ENOUGH`) 시 감상문 초안 생성 거절 |
| `TooManyRequestsException` | 429 | 호출 한도 초과 (`RateLimitInfo` 운반 — 아래 절 참조) | LLM rate limit, 자체 rate limiter |
| `ServiceUnavailableException` | 503 | 일시 장애 — 클라이언트 재시도 유도. `retryAfterSeconds` 필드(기본 30)가 `Retry-After` 헤더로 나감 | moderation 장애로 입력 안전 검사 불가 시 (`GUARDRAIL_MODERATION_UNAVAILABLE`) |
| `ExternalApiException` | 500 | 502/504 로 분류되지 않는 외부 API 실패 | 알라딘 기타 `RestClientException` (`LOOKUP_FAILED`/`SEARCH_FAILED`) |
| `BadGatewayException` | 502 | 외부 API 의 5xx 응답, 네트워크 IO 실패 | 알라딘 5xx (`*_GATEWAY_ERROR`), IO 실패 (`LOOKUP_IO_FAILURE`) |
| `GatewayTimeoutException` | 504 | 외부 API 타임아웃 | 알라딘 connect/read 타임아웃 (`*_TIMEOUT`) |

- `BusinessException` 은 `ErrorCode` 하나만 필드로 들고 있음
- 서브클래스는 원칙적으로 **HTTP 상태 매핑** 용도일 뿐 추가 필드 없음. 예외 두 개: `TooManyRequestsException` 은 `RateLimitInfo`, `ServiceUnavailableException` 은 `retryAfterSeconds` 를 운반한다 (둘 다 "언제 다시 시도할지"를 클라이언트에 전달하기 위한 필드)
- 세부 분기 정보는 `ErrorCode` enum 값으로 표현

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
- 책임: `BusinessException` 하위 타입별로 HTTP 상태 매핑 + `GlobalApiResponse.error(...)` 생성
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

> `GlobalExceptionHandler` 는 대부분의 경우 **건드릴 필요 없다**. 기존 HTTP 상태 서브클래스 안에서 끝난다.

## 신규 HTTP 상태 추가 (드문 경우)

기존 11개 서브클래스로 표현 불가능한 상태가 필요할 때만:

1. `domain/exception/{Status}Exception.java` 추가 (`extends BusinessException`, 외부 API 실패 계열이면 `extends ExternalApiException`)
2. `GlobalExceptionHandler` 에 `@ExceptionHandler({Status}Exception.class)` 핸들러 추가
3. 해당 ErrorCode 추가
4. 이 문서의 예외 계층 도표에 행 추가

> 참고로 `TooManyRequestsException(429)` 는 외부 LLM rate limit 매핑을 위해 (`AiChatErrorCode.AI_RATE_LIMIT_BURST` / `AI_QUOTA_EXHAUSTED`), `ServiceUnavailableException(503)` 은 moderation 장애 대응을 위해 도입됐다. 추가 전에 기존 분류로 표현 가능한지 먼저 검토.

## DB 계층 예외

- `DataAccessException` 는 `GlobalExceptionHandler` 에서 **503 Service Unavailable** 로 매핑 (이미 처리됨)
- JPA 제약 위반 등을 비즈니스 에러로 다시 던지고 싶을 때는 서비스 레이어에서 catch → `ConflictException` 등으로 rethrow

## 예외 발생 지점의 로그 레벨

[logging.md](logging.md) 의 일반 로그 레벨 표에서 한 단계 더 들어가, **예외를 잡거나 던지는 지점** 에서 어느 레벨로 남길지의 결정 기준.

핵심 원칙: **"누가, 어느 시급도로 봐야 하는 로그인가"** 로 정한다.

| 레벨 | 책임 주체 | 대응 시간 | HTTP/예외 매핑 |
|------|---------|---------|---------|
| ERROR | 운영자 (즉시 대응) | 분~시간 단위 | 5xx 미예상 장애, DB 연결 실패, 미분류 RuntimeException, **외부 시스템 한도/장애로 기능이 죽음** |
| WARN | 운영자/개발자 (모니터링) | 일 단위 | retry/fallback 후 회복, 보안 신호(토큰 재사용 감지 등), 명시적 폴백으로 사용자 영향 차단 |
| INFO | 비즈니스 흐름 | - | 주요 도메인 이벤트, 정상 처리 (예외 자체는 INFO 안 씀) |
| DEBUG | 개발 디버깅 | - | 4xx 사용자 입력 오류 (운영 시 비활성화) |

### 4xx 예외 (사용자 입력 / 인증 / 권한)

**원칙: 무로그 또는 DEBUG.** 이미 access log 에 기록되고, 사용자 측 문제라 운영자 대응이 불필요.

- 400/404/409: 무로그 또는 DEBUG. 컨트롤러 레벨 검증 실패는 굳이 별도 로그 안 남김
- 401/403: 무로그 또는 DEBUG. 단 **보안 모니터링이 필요한 케이스** (예: refresh token 재사용 감지) 는 WARN 한 번
- 429: 외부 시스템 한도 초과 → 아래 별도 항목

### 429 (TooManyRequestsException)

**원칙: ERROR.** 외부 LLM 한도 초과는 그 시점부터 해당 기능이 사용자에게 사실상 죽어 있는 상태이므로 즉시 인지가 필요.

> 보통 성숙한 시스템은 *단일 occurrence = WARN, 알람 시스템에서 빈도 기반으로 ERROR 승격* 으로 처리하지만, 우리는 메트릭/알람 인프라 도입 전이라 보수적으로 ERROR 통일.

세분화된 분기는 ErrorCode 에 둔다 (`AI_RATE_LIMIT_BURST` vs `AI_QUOTA_EXHAUSTED`). 알람 인프라 도입 후 BURST 만 WARN 으로 내릴지 재논의.

사용자별 한도 초과도 같은 429 로 매핑한다:
- `USER_RATE_LIMIT_EXCEEDED` — 상태 무관 USER 메시지 10초/5건 폭주 가드. Retry-After = 카운트 기간.
- `USER_TOKEN_BUDGET_EXCEEDED` — 사용자 토큰 예산(KST 4시간 창) 소진. Retry-After = 다음 창까지. `RateLimitInfo` 에 `limitTokens`·`remainingTokens(0)` 운반.

### 5xx (서버/외부 시스템 실패)

기본 ERROR. 단 **자체 회복 (retry 성공) / 명시적 폴백 (사용자 영향 차단)** 한 경우 WARN.

```java
// O — 외부 API 호출 실패 (분류되지 않은 모든 케이스)
log.error("[Stream] OpenAI API 호출 실패", error);

// O — retry 후 성공
log.warn("OpenAI 호출 재시도 성공 (시도 {}회)", attempts);
```

### IllegalStateException (프로그램 버그)

**ERROR.** 도달 불가 분기에 도달했거나 데이터 정합성이 깨진 상태. 프로덕션에서 발생하면 즉시 조사.

### 메시지 형식

- 한글 + 영문 기술 용어 (Token Pair / Rate Limit / Stream 등 영문 유지, 억지 번역 X)
- ERROR/WARN 은 원인 추적 가능하도록 컨텍스트 (외부 응답, ID, ErrorCode 이름 등) 포함
- ERROR 는 가능하면 throwable 동반 (`log.error("...", error)`) 으로 stack trace 보존

## 외부 시스템 응답 → 도메인 예외 분류

외부 API (OpenAI, 알라딘 등) 의 HTTP 응답을 도메인 예외로 변환할 때의 규칙.

### 메시지 문자열 키워드 매칭 금지

```java
// X — fragile. 외부 시스템의 메시지 포맷 변경에 깨지고, 분류 해상도가 낮음
String lower = error.getMessage().toLowerCase();
if (lower.contains("429") || lower.contains("quota")) {
    throw new TooManyRequestsException(...);
}
```

문제:
- 메시지 포맷은 라이브러리 버전 / 응답 본문에 의존 → 깨지기 쉬움
- "rate limit" / "quota exhausted" 가 같은 분기에 묶여 회복 시간이 다른 케이스 구분 못함
- HTTP status / 응답 헤더 / 본문 JSON 의 정보를 모두 잃음

### 가장 낮은 계층 (`ResponseErrorHandler`) 에서 typed 분기

Spring AI / RestClient 의 `ResponseErrorHandler` 를 직접 구현해 status code / headers / body JSON 을 typed 하게 본 후 도메인 예외로 변환한다. 어댑터(`infrastructure/{feature}/{provider}/`) 에 위치.

```java
// O — infrastructure/ai/openai/OpenAiResponseErrorHandler 의 패턴
@Override
public void handleError(URI url, HttpMethod method, ClientHttpResponse response) {
    HttpStatusCode statusCode = response.getStatusCode();
    HttpHeaders headers = response.getHeaders();
    String body = StreamUtils.copyToString(response.getBody(), UTF_8);

    if (statusCode.value() == 429) {
        // body 의 error.type 으로 BURST vs QUOTA_EXHAUSTED 구분
        // headers 의 retry-after / x-ratelimit-* 를 RateLimitInfo 로 추출
        throw classifyRateLimit(body, headers);
    }
    // ...
}
```

`OpenAiApi.Builder` 가 `ResponseErrorHandler` 를 받아주는 것처럼, 외부 API 클라이언트 라이브러리가 error handler 를 받아주면 그 자리에 주입. 안 받아주면 어댑터 메서드 안에서 status code 직접 분기.

## 429 의 RateLimitInfo 운반 패턴

429 응답은 단순 status code 만이 아니라 **언제 다시 시도해야 하는지** 를 함께 운반해야 클라이언트가 합리적으로 행동할 수 있다 (Issue #30 인수조건).

### 핵심 원칙

> **GlobalExceptionHandler 는 default 값을 갖지 않는다. 정책은 정보의 출처가 결정한다.**

각 외부 시스템마다 한도 종류 (RPM/TPM/quota/시간별 등) 와 회복 시간이 다르므로, "Retry-After: 30" 같은 전역 default 를 박으면 거의 항상 거짓말이 된다. **던지는 쪽 (외부 시스템 어댑터) 이 자기 응답에서 직접 추출한 값** 만 운반한다.

### 데이터 흐름

```text
OpenAI 응답 (status 429 + headers)
        │
        ▼
OpenAiResponseErrorHandler (어댑터)
  - body 의 error.type 으로 BURST vs QUOTA_EXHAUSTED 분류
  - headers 의 retry-after / x-ratelimit-* 를 RateLimitInfo 로 추출
        │
        ▼
TooManyRequestsException(errorCode, rateLimitInfo) 생성 후 throw
        │
        ├─→ pre-stream 경로: GlobalExceptionHandler
        │     - HTTP 429 상태로 응답
        │     - rateLimitInfo.toHttpHeaders() 를 응답 헤더에 그대로 매핑
        │
        └─→ mid-stream 경로 (SSE): AiChatMessageSendService
              - 응답이 이미 commit (status 200) 이라 헤더 사용 불가
              - rateLimitInfo 를 SSE error event 의 payload 로 운반
```

### `RateLimitInfo` 의 책임

- 모든 필드는 nullable. 외부 시스템마다 채워지는 필드가 다르므로 **채워진 것만** 응답에 반영, 누락 필드는 헤더/payload 키 자체가 생략된다.
- 캐논화된 필드 ↔ 헤더명 / payload 키 매핑은 `RateLimitInfo` 내부 enum 한 곳에서 정의 (`toHttpHeaders()` / `toPayloadMap()`). consumer 는 1라인으로 호출.

### `GlobalExceptionHandler` 의 역할 (운반자)

```java
@ExceptionHandler(TooManyRequestsException.class)
public ResponseEntity<...> handleTooManyRequests(TooManyRequestsException ex) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
    RateLimitInfo info = ex.getRateLimitInfo();
    if (info != null) {
        info.toHttpHeaders().forEach(builder::header);   // ← 운반만, 정책 모름
    }
    return builder.body(...);
}
```

미래에 다른 외부 시스템 (예: 알라딘) 이 429 를 던질 때도 GlobalExceptionHandler 는 변경하지 않는다. 새 어댑터가 자기 응답을 보고 다른 RateLimitInfo 를 채워 던지면 끝.

## SSE mid-stream 에러 처리

SSE 엔드포인트에서 OpenAI 호출은 **Flux subscribe 시점** = 응답이 이미 commit (status 200, Content-Type: text/event-stream) 된 후에 발생한다. 이 시점에 던져진 예외는:

- `@ExceptionHandler` 가 잡지 못함 (응답이 이미 나가는 중)
- HTTP status / 헤더 변경 불가
- 대신 SSE error event 로 변환되어 stream 안에서 흘러감

**규칙**:
- pre-stream 단계 (검증/DB/사용자메시지 영속화) 의 예외 → `GlobalExceptionHandler` 정상 경로 (4xx/5xx + 헤더)
- mid-stream 단계의 예외 → `onErrorResume` 으로 잡아 `MessageStreamEvent.Error` 로 변환, 직렬화기가 `event: error` 로 emit
- 429 의 RateLimitInfo 도 mid-stream 이면 헤더 대신 SSE error payload 의 `rateLimit` 키로 운반 (정보 손실 방지)

미들웨어 (Spring 의 reactive return type handler) 는 두 단계를 자동으로 분리해 주므로 서비스/컨트롤러에서 명시적으로 분기할 필요 없음.
