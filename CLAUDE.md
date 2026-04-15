# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**readwith** — a Spring Boot 4.0.5 web application using Java 25, Gradle 9.4.1, and Lombok.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.readwith.SomeTest"

# Run a single test method
./gradlew test --tests "com.readwith.SomeTest.methodName"

# Clean build
./gradlew clean build
```

## Architecture

- **Base package**: `com.readwith` — Spring Boot auto-scans from here
- **Framework**: Spring Boot 4.0.5 with `spring-boot-starter-webmvc` (servlet-based web)
- **Build**: Gradle with `io.spring.dependency-management` plugin for BOM-managed dependencies
- **Java version**: 25 (toolchain-enforced)
- **Lombok**: Available in both main and test source sets (compileOnly + annotationProcessor)
- **Testing**: JUnit 5 via `junit-platform-launcher`; Spring test support via `spring-boot-starter-webmvc-test`

## Package Structure (DDD 3-Layer)

```
com.readwith
├── presentation
│   └── controller
│       └── {feature}/          # REST 컨트롤러 (기능 단위)
├── domain
│   └── {feature}
│       └── service/            # 비즈니스 로직 (기능 단위)
└── entity
    └── {table}
        ├── entity/             # JPA 엔티티 (테이블 단위)
        └── repository/         # Spring Data Repository
```

## API Response Format

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

## Testing Conventions

- 서비스(Application) 레이어는 반드시 단위 테스트를 작성한다.
- 조회 기능은 DAO 통합 테스트를 반드시 수행한다.
- 테스트 메서드 네이밍: 행위를 설명하는 한글 이름을 허용한다.
  ```java
  @Test
  void 존재하지_않는_사용자를_조회하면_예외가_발생한다() { ... }
  ```

## Logging Levels

| 레벨 | 용도 | 예시 |
|------|------|------|
| ERROR | 즉시 대응이 필요한 장애 | 외부 API 실패, DB 연결 실패 |
| WARN | 잠재적 문제 | 재시도 성공, 폴백 동작 |
| INFO | 주요 비즈니스 흐름, I/O | 사용자 가입, 주문 생성 |
| DEBUG | 개발 디버깅 용도 | 운영 환경에서는 비활성화 |

## Git Conventions

- **Branch naming**: `main ← dev ← feature|bugfix|hotfix/{이슈번호}-{간단한-설명}`
- **Commit message**: Angular Convention
  ```
  <type>(<scope>): <subject>
  ```
  | type | 설명 |
  |------|------|
  | feat | 새로운 기능 추가 |
  | fix | 버그 수정 |
  | docs | 문서 변경 |
  | style | 코드 포맷팅 (기능 변경 없음) |
  | refactor | 리팩토링 (기능 변경 없음) |
  | test | 테스트 추가/수정 |
  | chore | 빌드, 설정 등 기타 변경 |
