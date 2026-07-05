# 인증 구조

## 인증 필터 체인

신원 해석은 `presentation/common/security` 의 인증 필터 두 개가 전담한다
(`SecurityConfig` 등록 순서대로).

1. `JwtAuthenticationFilter` — `Authorization: Bearer` Access Token 이 있으면 먼저 인증한다.
2. `SessionCookieAuthenticationFilter` — 앞 단계에서 인증이 채워지지 않았을 때만
   `user_session` 쿠키를 `SessionAuthenticationService` 로 해석해 principal(userId) 을 채운다.

- **컨트롤러로의 전달**: 어느 경로로 인증됐든 컨트롤러는 쿠키·SecurityContext 를 직접
  만지지 않고 `@AuthenticatedUserId Long userId`(같은 패키지의 `AuthenticatedUserId.java`) 로
  사용자 식별자만 받는다.
- **금지 (보안 패키지 밖 전체에 적용 — 서비스에 한정되지 않음)**: `SecurityContextHolder`
  직접 참조, `user_session` 쿠키를 `@CookieValue` 로 직접 수신, `UserRepository.findBySessionId`
  직접 호출(신원 해석 대행).

이 경계는 ArchUnit 테스트
`src/test/java/com/readum/architecture/AuthenticationBoundaryArchTest.java` 의 규칙 3개로 빌드 단계에서 강제된다:

- `SecurityContextHolder` 는 `presentation/common/security` 패키지 밖에서 직접 참조하지 않는다
- `user_session` 쿠키는 컨트롤러가 `@CookieValue` 로 직접 받지 않는다
- `UserRepository.findBySessionId` 는 `SessionAuthenticationService` 만 호출한다

## AI 채팅 rate limit

AI 채팅 호출은 사용자별로 한도를 건다. rate limit 키는 `userId`
(`AiChatRateLimitInterceptor.java:67`) 이며, 한도 값은 `application-dev.yml` 의 `GuardrailProperties`
(분당 20회, 하루 200회 — 개발 서버 값) 에서 온다.

판정 정책 상세는 [docs/record/0004-aichat-guardrail-flow.md](../record/0004-aichat-guardrail-flow.md) 참조.
