# Service 패턴 · Port/Adapter 패턴

> 적용 대상: 서비스 클래스를 만들 때, 외부 시스템을 추상화하는 Port/Adapter 를 도입할지 정할 때.

## Service 패턴

- 인터페이스 없이 구체 클래스 사용 (`@Service`, `@RequiredArgsConstructor`)
- `Impl` 접미사 사용 금지 (Service 에 한함)
- **Command 서비스** (변경 작업): 단일 `execute(Command)` 메서드 -> Result 반환
- **Query 서비스** (조회 작업): 메서드명으로 의도를 드러내며, 여러 메서드 허용
- **도메인 빌딩 블록** (토큰 생성·파싱, 암호화 등): Command/Query 서비스 형태에 맞지 않는 인프라 의존 동작은 `domain/{feature}/out/` 에 Port 인터페이스로 정의하고, 구현체는 `infrastructure/{...}/` 에 Adapter (`{Port}Impl`) 로 둔다. 예: `domain/auth/out/TokenGenerator` ← `infrastructure/security/jwt/JwtTokenGeneratorImpl`

## Port/Adapter 패턴

- **Port** 인터페이스: `domain/{feature}/out/` 에 위치
- **Adapter** 구현체: `infrastructure/{feature}/{provider}/` 에 위치, `Impl` 접미사 사용
- **적용 기준 두 가지를 모두 만족할 때만 도입한다**:
  1. 그 동작을 **domain 이 호출한다** (Port 는 domain 이 인프라 구현을 모르게 하는 장치다 — 소비자가 인프라뿐이면 교체 여지가 있어도 인프라 안에서 해결한다)
  2. 구현체가 교체될 여지가 있거나 외부 시스템(API, 메시징, 캐시 백엔드 등)을 추상화해야 한다 (단순 JPA Repository 접근은 `Service → Repository` 직접 호출로 충분)
- 사례:
  - O `TokenBlacklistStore` — domain/auth 서비스가 호출 + 현재 in-memory (Caffeine) 구현이지만 Redis 등으로 교체 여지
  - O `SummaryCallRateLimiter` — domain 의 `SummaryGenerationWorker` 가 호출 + in-memory 구현 교체 여지
  - X `RefreshTokenStore` — JPA Repository 를 단순 래핑하는 수준이라 Port 없이 Service 가 `RefreshTokenRepository` 를 직접 사용 (기준 2 불충족)
  - O `ChatTokenBudget` — 사용자별 토큰 예산이 도메인 규칙이 되면서 Port 승격 조건("rate limit 정책 판단이 도메인 규칙이 되는 시점")이 발동된 사례. Redis 어댑터 구현 (2026-07-06, 구 `AiChatRateLimiter` 인터셉터는 제거됨)
