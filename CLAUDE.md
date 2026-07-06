# CLAUDE.md

AI 작업자를 위한 최상단 라우터 문서. 상세 규칙은 링크된 문서가 원본(canonical)이고, 이 파일에는 매 세션 반드시 지켜야 할 것만 남긴다. 전체 문서 색인은 [docs/README.md](docs/README.md).

## 프로젝트 한 줄

**readum** — AI 와 대화하며 독서 감상을 기록하는 서비스의 백엔드 서버. Spring Boot 4.0.5 · Java 25 · Gradle 9.4.1 · 단일 모듈.

## ⚠️ 작업 지시 (절대 규칙)

- **이 파일(CLAUDE.md)을 임의로 수정하거나 내용을 삭제하지 말 것.**
- 작업 지시에 명시된 파일만 생성/수정할 것. 범위 외 파일은 건드리지 말 것.
- 기존 소스 파일을 "불필요하다"고 판단해 삭제하지 말 것. 삭제는 명시적으로 요청받은 경우에만 수행할 것.

## 어휘 / 용어 작성 규칙 (문서·코드·대화 공통, 매우 중요)

- **그 단어만 따로 봤을 때 무엇을 가리키는지 바로 이해되지 않는 용어는 쓰지 않는다.** 쉽게 이해되는 우리말로 풀어 말하거나 쓴다. 추상 영어 jargon 뿐 아니라 `Tier`, `production` 처럼 영어가 아니어도 맥락 없이는 모호한 약칭·번호 라벨도 포함된다.
- **예외 — 그대로 써도 되는 것**: 널리 통용되는 보편 전문 용어(예: `Trade-off`), 코드 식별자·클래스/메서드 이름, 표준 스펙(`GET`, `400`, `JWT`, `JPA`), 정착된 약어. 처음 등장하는 기술 용어는 짧은 풀이를 한 번 덧붙인다.
- 판단 기준: **그 단어만 따로 봤을 때 팀원이 바로 이해하는가?** 아니면 풀어 쓴다.
- 피할 표현 → 한국어 대체의 상세 표는 [`docs/conventions/vocabulary.md`](docs/conventions/vocabulary.md) 를 따른다 (문서·PR·이슈·주석·커밋 메시지·로그 공통 적용).

## 작업 전 읽을 문서

- 모든 구현 작업: [docs/ai-workflow/task-workflow.md](docs/ai-workflow/task-workflow.md) (작업 순서) + [docs/conventions/README.md](docs/conventions/README.md) (규칙 색인)
- 구조 파악: [docs/architecture/system-overview.md](docs/architecture/system-overview.md), 기능이 어느 도메인에 있는지는 [docs/domain/README.md](docs/domain/README.md)
- 운영·인프라·로컬 실행이 걸린 작업: [docs/ops/README.md](docs/ops/README.md)

## Core Rules

위반이 잦은 핵심 규칙 요약. 상세·예시·예외 기준은 각 링크 문서를 따른다.

1. **계층 의존 방향**: presentation → domain → model, infrastructure → domain. 역방향·건너뛰기(presentation → model, presentation → infrastructure, domain → infrastructure 구현체) 금지 → [package-structure](docs/conventions/package-structure.md)
2. **Service**: Command 서비스는 단일 `execute(Command)` → Result, Query 서비스는 `{Domain}SearchService`. 인터페이스 없이 구체 클래스, Service 에 `Impl` 접미사 금지 → [service-and-port](docs/conventions/service-and-port.md)
3. **DTO**: Request 는 `toCommand()`, Response 는 `from(Result)`. Entity → Result 변환은 DTO 의 `from(...)` 정적 팩토리가 담당 (service 내 변환 헬퍼 금지, 예외 기준은 문서) → [dto](docs/conventions/dto.md)
4. **Entity**: 불변식을 강제하는 `create()` 계열 도메인 팩토리만 노출. `of()`(전체 필드 지정)·setter 금지. 테스트 상태 조립은 같은 패키지의 `{Entity}Fixture` → [entity](docs/conventions/entity.md)
5. **예외**: `BusinessException` 서브클래스(HTTP 상태) + `ErrorCode`(세부 분기) 조합. raw `RuntimeException`/`IllegalArgumentException` 금지, 매핑은 `GlobalExceptionHandler` 한 곳에만 → [exception-handling](docs/conventions/exception-handling.md)
6. **인증 신원**: `SessionCookieAuthenticationFilter` 가 해석해 컨트롤러에 `@AuthenticatedUserId Long userId` 로만 전달. 서비스에서 쿠키/세션을 직접 읽는 패턴 금지 (ArchUnit 으로 강제) → [security-architecture](docs/architecture/security-architecture.md)
7. **테스트**: 서비스 레이어 단위 테스트 필수, 조회 기능은 DAO 통합 테스트 필수. 테스트 이름은 행위를 주장하는 한글 문장 → [testing](docs/conventions/testing.md)
8. **API/Swagger**: 경로 `/api/v1/{resource}`, 컨트롤러에 `@Tag`, 메서드에 `@Operation` + `@ApiResponses` 필수. 응답 wrapper 는 `GlobalApiResponse` → [api-and-swagger](docs/conventions/api-and-swagger.md)
9. **트랜잭션**: 단일 Repository 호출만 하는 조회 서비스에 `@Transactional(readOnly = true)` 를 붙이지 않는다. 외부 HTTP 호출(알라딘·LLM 등)은 `@Transactional` 밖에서 하고, 원자적 DB 쓰기만 협력자 빈(`{동작대상}Writer`/`Reader`, package-private)의 선언적 `@Transactional` 에 위임한다. `TransactionTemplate` 금지 (부착·분리·미분리 기준은 문서) → [transaction](docs/conventions/transaction.md)
10. **설정값**: 시크릿·환경별 값은 환경변수와 `application-{profile}.yml`, 환경 무관 비즈니스 룰은 `application.yml` 직접값 → [configuration](docs/conventions/configuration.md)

이 밖의 규칙(JPQL 작성, 네이밍 표, 로그 레벨 등)은 [docs/conventions/README.md](docs/conventions/README.md) 색인에서 찾는다.

## 빌드 & 실행

```bash
./gradlew build          # 빌드
./gradlew test           # 전체 테스트
./gradlew test --tests "com.readum.SomeTest"   # 단건
./gradlew bootRun        # 실행 (환경 변수는 src/main/resources/application*.yml 의 ${...} 참조)
```

## Git

- 브랜치: `main ← dev ← feature|bugfix|hotfix/{이슈번호}-{간단한-설명}`, PR base 는 `dev`
- 커밋: Angular convention `<type>(<scope>): <subject>` (feat/fix/docs/style/refactor/test/chore)
- 상세와 PR·이슈 생성 절차: [docs/ai-workflow/git-and-pr.md](docs/ai-workflow/git-and-pr.md)

## Decision Records

기술적 의사결정 이력은 [docs/record/](docs/record/README.md) 에서 관리한다. 별도의 ADR 디렉토리는 만들지 않는다. 의사결정 배경이 필요하면 docs/record/ 를 확인하되, 현재 작업의 구현 규칙은 docs/conventions/ 와 docs/architecture/ 를 우선한다.
