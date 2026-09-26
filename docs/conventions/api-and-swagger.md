# API · Swagger 규칙

> 적용 대상: 컨트롤러/엔드포인트를 만들 때, Swagger 문서 어노테이션과 응답 형식을 작성할 때.

## API 규칙

- RESTful 원칙 준수
- 경로 패턴: `/api/v1/{resource}`
- `ResponseEntity` 로 HTTP 상태 코드 명시

## Swagger 규칙

### 컨트롤러 클래스: `@Tag` 필수

Swagger UI 의 그룹명이 클래스명(예: `ai-chat-controller`)으로 표시되지 않도록 모든 컨트롤러에 한국어 라벨을 붙인다.

```java
@Tag(name = "AI 채팅", description = "AI 와 책 한 권에 대해 대화하는 채팅 세션 및 메시지 관리")
@RestController
@RequestMapping("/api/v1/ai-chat")
public class AiChatController { ... }
```

- `name`: 사람이 읽기 쉬운 한국어 (예: "AI 채팅", "내 책장", "도서 검색", "인증")
- `description`: 해당 그룹이 담당하는 API 의 한 줄 설명

### 메서드: `@Operation` + `@ApiResponses` 필수

```java
@Operation(
        summary = "한 줄 요약 (명사형)",
        description = "상세 설명. 동작 흐름, 조건, 예외 상황을 포함한다."
)
@ApiResponses({
        @ApiResponse(responseCode = "201", description = "성공 설명"),
        @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
        // 해당 엔드포인트에서 발생 가능한 응답 코드만 포함
})
public ResponseEntity<GlobalApiResponse<XxxResponse>> handler(...) {
    return GlobalApiResponse.ok(...);
}
```

### 응답 wrapper 명명: `GlobalApiResponse`

성공/에러 응답 wrapper 의 클래스 이름은 **`GlobalApiResponse`** (위치: `com.readum.presentation.common.GlobalApiResponse`).

이름이 `Response` 가 아닌 `GlobalApiResponse` 인 이유: `io.swagger.v3.oas.annotations.responses.ApiResponse` 와 클래스 이름(simple name)이 충돌하면 둘 다 import 할 수 없기 때문. swagger 어노테이션은 메서드당 4~5개씩 등장해 짧게 쓰는 게 우선이라, 우리 wrapper 이름을 충돌하지 않는 형태로 잡았다.

- swagger: `import io.swagger.v3.oas.annotations.responses.ApiResponse;` → `@ApiResponse(...)`
- 우리 wrapper: `import com.readum.presentation.common.GlobalApiResponse;` → `GlobalApiResponse<X>`, `GlobalApiResponse.ok(...)`

신규 컨트롤러 작성 시 두 import 를 같이 두면 충돌 없이 짧게 쓸 수 있다.

## API 응답 형식

- 성공 응답: `status 2XX`
  - data 블럭 내부에 단수는 `단수명사: {}`, 복수는 `복수명사: []`
  ```json
  {
    "data": {
      "user": { ... },
      "profiles": [ { ... }, ... ]
    }
  }
  ```
- 에러 응답: `status 4XX/5XX`
  ```json
  {
    "error": {
      "message": "이메일 형식이 올바르지 않습니다."
    }
  }
  ```
