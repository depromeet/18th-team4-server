# 인증 (auth) 시나리오

## 1. 요청 인증 (필터 체인)

모든 보호된 API 요청은 두 인증 필터(JWT → 세션 쿠키)가 신원을 principal(userId) 로 해석하고, 인가 규칙이 401 판정을 담당하며, 컨트롤러는 `@AuthenticatedUserId` 로만 userId 를 받는다.

```mermaid
sequenceDiagram
    participant Client as 클라이언트
    participant JwtFilter as JwtAuthenticationFilter
    participant TokenAuth as TokenAuthenticationService
    participant SessionFilter as SessionCookieAuthenticationFilter
    participant SessionAuth as SessionAuthenticationService
    participant Authz as SecurityConfig 인가 규칙
    participant Resolver as AuthenticatedUserIdArgumentResolver
    participant Controller as 컨트롤러

    Client->>JwtFilter: 요청 (Authorization Bearer 헤더 / user_session 쿠키)
    alt Bearer 헤더 있음
        JwtFilter->>TokenAuth: authenticate(accessToken)
        alt 검증 성공 (서명·만료·issuer 확인, typ=access, 블랙리스트 미등록)
            TokenAuth-->>JwtFilter: AuthenticatedPrincipal(userId, role)
            JwtFilter->>JwtFilter: SecurityContext 에 principal=userId, ROLE_역할 저장 + 요청 attribute 에 accessToken 저장
        else UnauthorizedException (TOKEN_EXPIRED / INVALID_TOKEN / TOKEN_REVOKED)
            JwtFilter->>JwtFilter: SecurityContext 비우고 통과 (401 판정은 인가 규칙에 위임)
        end
    else Bearer 헤더 없음
        JwtFilter->>JwtFilter: 통과
    end
    JwtFilter->>SessionFilter: chain.doFilter
    alt 이미 principal 채워짐 (JWT 인증 성공)
        SessionFilter->>SessionFilter: 통과
    else user_session 쿠키 있음
        SessionFilter->>SessionAuth: authenticate(userSessionId)
        alt UserRepository.findBySessionId 로 사용자 존재
            SessionAuth-->>SessionFilter: userId
            SessionFilter->>SessionFilter: SecurityContext 에 principal=userId, ROLE_USER 저장
        else UnauthorizedException(INVALID_SESSION)
            SessionFilter->>SessionFilter: SecurityContext 비우고 통과
        end
    else 쿠키 없음
        SessionFilter->>SessionFilter: 통과
    end
    SessionFilter->>Authz: chain.doFilter
    alt permitAll 경로 (auth/refresh, auth/logout, users/sessions, books, swagger, 일부 actuator)
        Authz->>Controller: 인증 없이 진입 허용
    else 보호된 경로 + principal 있음
        Authz->>Resolver: 핸들러 호출
        Resolver->>Controller: @AuthenticatedUserId Long userId 주입 (principal 이 Long 이 아니면 IllegalStateException — 보안 설정 버그)
    else 보호된 경로 + principal 없음
        Authz-->>Client: JwtAuthenticationEntryPoint 가 401 UNAUTHORIZED 응답
    end
```

## 2. Access Token 재발급 (Refresh Token rotation)

`POST /api/v1/auth/refresh` — 쿠키의 Refresh Token 을 검증해 새 Token Pair 를 발급하고, 기존 토큰은 회전(rotation) 처리한다. 회전 직후 유예 기간(grace period) 내 재사용은 기존 child 토큰을 다시 내려주고, 유예를 지난 재사용은 재사용 감지로 그 사용자의 Refresh Token 전체를 폐기한다.

```mermaid
flowchart TD
    A["POST /api/v1/auth/refresh<br/>(refresh_token 쿠키, permitAll)"] --> B["TokenRefreshService.execute<br/>TokenGenerator.parse 로 JWT 검증"]
    B -->|"서명 무효/만료"| E401A["401 INVALID_TOKEN / TOKEN_EXPIRED"]
    B -->|"typ 이 refresh 아님"| E401B["401 INVALID_TOKEN"]
    B -->|파싱 성공| C["RefreshTokenRepository.findByUserIdAndJwtId<br/>DB 행 조회"]
    C -->|행 없음| E401C["401 REFRESH_TOKEN_NOT_FOUND"]
    C -->|행 있음| D{"revokedAt 있음?<br/>(이미 폐기됨)"}
    D -->|예| E401D["401 REFRESH_TOKEN_REUSE_DETECTED"]
    D -->|아니오| F{"expiresAt 지남?"}
    F -->|예| E401E["401 REFRESH_TOKEN_EXPIRED"]
    F -->|아니오| G{"회전됨 + graceExpiresAt 이전?<br/>(유예 기간 내 재사용)"}
    G -->|예| H["findByParentJwtId 로 child 토큰 조회<br/>child 의 Refresh Token + 새 Access Token 재발급"]
    H --> OK["200 — 새 Access Token 응답 본문<br/>Refresh Token 은 HttpOnly 쿠키(Set-Cookie)"]
    G -->|"아니오, rotatedAt 있음<br/>(유예 지난 재사용)"| I["revokeAllByUserId<br/>해당 사용자 Refresh Token 전체 폐기"]
    I --> E401D
    G -->|"아니오, 미회전 (정상)"| J["rotate — 조건부 update<br/>(미회전·미폐기·미만료 행만 rotatedAt/graceExpiresAt 기록)"]
    J -->|"affected == 1"| K["child RefreshToken 행 저장<br/>(parentJwtId = 기존 jwtId)"]
    K --> L["새 Access/Refresh Token Pair 생성"]
    L --> OK
    J -->|"affected == 0 (동시 요청 경합)"| M["행 재조회 후 재분류:<br/>없음/폐기/만료/유예/그 외"]
    M -->|없음| E401C
    M -->|폐기됨| E401D
    M -->|만료됨| E401E
    M -->|유예 기간 내| H
    M -->|"그 외 (유예 지난 회전)"| I
```

## 3. 로그아웃

`POST /api/v1/auth/logout` — Refresh Token 을 전부 폐기하고 Access Token 을 블랙리스트에 등록하며, 토큰이 없거나 무효여도 항상 204 로 응답한다 (멱등).

```mermaid
flowchart TD
    A["POST /api/v1/auth/logout<br/>(refresh_token 쿠키 optional, permitAll)"] --> B["LogoutService.execute<br/>refresh_token 파싱 (typ=refresh 확인)"]
    B -->|"없음 / 파싱 실패 / 타입 불일치"| C["익명 처리 — 아무것도 폐기하지 않음<br/>LogoutResult.anonymous"]
    B -->|유효| D["revokeAllByUserId<br/>해당 userId 의 Refresh Token 전체 폐기 (revokedAt 기록)"]
    D --> E["요청 attribute 의 Access Token 파싱<br/>(JwtAuthenticationFilter 가 저장해 둔 값)"]
    E -->|"유효 + userId 일치 + 만료 전"| F["TokenBlacklistStore.add<br/>남은 TTL 만큼 jwtId 블랙리스트 등록 (Caffeine in-memory)"]
    E -->|"없음 / 무효 / userId 불일치 / 이미 만료"| G["블랙리스트 등록 생략"]
    F -->|"등록 실패 (RuntimeException)"| H["ERROR 로그만 남기고 계속 진행 — 등록 실패가 로그아웃을 막지 않음"]
    C --> Z["204 No Content<br/>refresh_token 쿠키 즉시 만료 (Set-Cookie maxAge=0)"]
    F --> Z
    G --> Z
    H --> Z
```

## 4. Token Pair 발급 (TokenIssueService)

로그인 성공 시 새 Access/Refresh Token Pair 를 만들고 Refresh Token 을 DB 에 저장하는 서비스 — 아직 호출하는 컨트롤러가 없다 (소셜 로그인 도입 대비, 현재는 테스트에서만 사용).

```mermaid
flowchart TD
    A["TokenIssueService.execute<br/>TokenIssueCommand(userId, role)"] --> B["accessJwtId / refreshJwtId<br/>UUID 각각 생성"]
    B --> C["TokenGenerator 로 Access Token 생성<br/>(TTL = auth.access-token-ttl)"]
    C --> D["TokenGenerator 로 Refresh Token 생성<br/>(TTL = auth.refresh-token-ttl)"]
    D --> E["RefreshToken.create 행 저장<br/>(userId, refreshJwtId, issuedAt, expiresAt)"]
    E --> F["TokenPair 반환<br/>(accessToken, refreshToken, 각 TTL)"]
```
