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
- **적용 기준**: 구현체가 교체될 여지가 있거나 외부 시스템(API, 메시징, 캐시 백엔드 등)을 추상화해야 할 때만 도입한다. 단순 JPA Repository 접근은 `Service → Repository` 직접 호출로 충분하므로 Port 를 두지 않는다.
  - O `TokenBlacklistStore` — 현재 in-memory (Caffeine) 구현이지만 Redis 등으로 교체 여지가 있어 Port 유지
  - X `RefreshTokenStore` — JPA Repository 를 단순 래핑하는 수준이라 Port 없이 Service 가 `RefreshTokenRepository` 를 직접 사용
