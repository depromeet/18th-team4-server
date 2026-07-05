# 인증 구조

## 세션 쿠키 기반 인증

인증은 세션 쿠키로 이루어진다.

- **신원 해석 전담**: `presentation/common/security/SessionCookieAuthenticationFilter` 가
  요청의 세션 쿠키를 읽어 신원을 해석한다. 신원 해석은 이 필터 한 곳에서만 한다.
- **컨트롤러로의 전달**: 컨트롤러는 세션·쿠키를 직접 만지지 않고
  `@AuthenticatedUserId Long userId`(같은 패키지의 `AuthenticatedUserId.java`) 로 사용자 식별자만 받는다.
- **금지**: 서비스에서 쿠키/세션을 직접 읽는 구 패턴은 쓰지 않는다.

이 경계 규칙은 ArchUnit 테스트
`src/test/java/com/readum/architecture/AuthenticationBoundaryArchTest.java` 로 강제된다.

## AI 채팅 rate limit

AI 채팅 호출은 사용자별로 한도를 건다. rate limit 키는 `userId`
(`AiChatRateLimitInterceptor.java:67`) 이며, 한도 값은 `application-dev.yml` 의 `GuardrailProperties`
(분당 20회, 하루 200회 — 개발 서버 값) 에서 온다.

판정 정책 상세는 [docs/record/0004-aichat-guardrail-flow.md](../record/0004-aichat-guardrail-flow.md) 참조.
