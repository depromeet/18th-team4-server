# 외부 연동

서버가 붙는 외부 시스템과, 이를 감싸는 Port/Adapter 구성.

## OpenAI

Spring AI 의 `spring-ai-starter-model-openai` (BOM 2.0.0-M4, build.gradle:32) 로 연동한다.

- **채팅 스트리밍(SSE)**: AI 대화 응답을 스트리밍으로 내려보낸다.
- **입력 moderation**: 사용자 입력 검사는 Spring AI 의 기본 WebClient 대신
  블로킹 JDK `HttpClient`(HTTP/1.1) 로 직접 구성한다 —
  `infrastructure/ai/openai/OpenAiHttpClientConfig.java`.
  이 구조 결정의 배경은 [docs/record/0003](../record/0003-aichat-concurrency-moderation-http-client.md) 참조.
  Port `domain/aiChat/out/InputModerationClient` ← Adapter `OpenAiInputModerationClientImpl`.
- **출력 검사**: `ModerationOutputAdvisor` 가 AI 응답에 대한 검사를 담당한다.

## 알라딘 도서 API

도서 검색·조회를 알라딘 API 프록시로 처리한다 (`infrastructure/book/aladin/`).

- `AladinBookSearchClientImpl` — 키워드 검색
- `AladinBookLookupClientImpl` — 단건 조회
- `AladinItemSearchResponse` — 외부 응답 매핑
- `AladinProperties` — API 설정
- `AladinHttpConfig` — HTTP 클라이언트 구성

외부 응답을 도메인 예외로 변환하는 규칙은
[docs/conventions/exception-handling.md](../conventions/exception-handling.md) 참조.

## Caffeine (인메모리 캐시)

외부 저장소 없이 프로세스 안에서 관리하는 상태를 Caffeine 으로 둔다.

- **토큰 블랙리스트**: Port `domain/auth/out/TokenBlacklistStore` ←
  Adapter `infrastructure/auth/inmemory/TokenBlacklistStoreImpl`.
- **rate limit 버킷**: bucket4j + Caffeine, `infrastructure/ai/openai/ratelimit/AiChatRateLimiter`.

## Port / Adapter 전체 목록

`domain/*/out/` 의 Port 전부와 대응 Adapter (example 제외).

| Port (domain/*/out/) | Adapter (infrastructure) | 용도 |
|------|------|------|
| `aiChat/out/AiChatClient` | `ai/openai/AiChatClientImpl` | OpenAI 채팅 스트리밍 |
| `aiChat/out/AiChatTitleClient` | `ai/openai/AiChatTitleClientImpl` | 세션 제목 자동 생성 |
| `aiChat/out/AiSummaryClient` | `ai/openai/AiSummaryClientImpl` | 감상문 초안 생성 |
| `aiChat/out/InputModerationClient` | `ai/openai/OpenAiInputModerationClientImpl` | OpenAI 입력 moderation |
| `auth/out/TokenGenerator` | `security/jwt/JwtTokenGeneratorImpl` | JWT 생성·파싱 |
| `auth/out/TokenBlacklistStore` | `auth/inmemory/TokenBlacklistStoreImpl` (Caffeine) | 로그아웃한 토큰 블랙리스트 |
| `book/out/BookSearchClient` | `book/aladin/AladinBookSearchClientImpl` | 알라딘 키워드 검색 |
| `book/out/BookLookupClient` | `book/aladin/AladinBookLookupClientImpl` | 알라딘 단건 조회 |
| `summary/out/SummaryCallRateLimiter` | `ai/openai/ratelimit/SummaryCallRateLimiterImpl` | 감상문 생성 호출 속도 제어 |
| `summary/out/SummaryCallBreaker` | `ai/openai/circuitbreaker/InMemorySummaryCallBreaker` | 감상문 생성 circuit breaker |

새 연동을 추가할 때 이 표에 함께 채워 넣는다.

> `AiChatRateLimiter` 는 Port 가 없다 — 같은 infrastructure 계층의
> `AiChatRateLimitInterceptor` 만 사용하므로 domain 을 거치지 않는다.
