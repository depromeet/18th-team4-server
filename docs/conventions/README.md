# 코드 작성 규칙 (conventions)

> 적용 대상: 이 저장소에서 코드를 구현하는 모든 작업. 구현 중 지켜야 하는 코드 작성 규칙을 모아 둔다.

기능을 만들거나 고칠 때 참조하는 규칙 문서 모음이다. **이 디렉토리가 각 규칙의 원본(canonical)이다.** 루트 `CLAUDE.md` 는 라우터 문서로, 위반이 잦은 핵심 규칙 요약과 이 디렉토리로의 링크만 둔다.

| 파일 | 다루는 규칙 |
|------|-------------|
| [package-structure.md](package-structure.md) | 4계층 패키지 구조와 계층 간 의존 방향 |
| [service-and-port.md](service-and-port.md) | Service 패턴(Command/Query)과 Port/Adapter 패턴 |
| [dto.md](dto.md) | DTO 규칙과 Entity → Result 변환 책임 |
| [entity.md](entity.md) | 엔티티 작성 규칙과 테스트 Fixture 규칙 |
| [jpql.md](jpql.md) | JPQL/쿼리 작성(derived query 우선, alias, 쉼표 위치) |
| [transaction.md](transaction.md) | `@Transactional(readOnly = true)` 부착 기준, 외부 I/O 트랜잭션 밖 분리·협력자 빈 기준 (`TransactionTemplate` 금지) |
| [api-and-swagger.md](api-and-swagger.md) | API 경로/응답 형식과 Swagger 문서화 |
| [naming.md](naming.md) | 클래스/DTO/Port 등 이름 규칙 |
| [vocabulary.md](vocabulary.md) | 어휘·용어 작성 규칙 — 글 전반(문서·PR·주석·커밋·로그) 공통 (원본 문서) |
| [configuration.md](configuration.md) | `@ConfigurationProperties` 배치 규칙 |
| [logging.md](logging.md) | 로그 레벨과 로그 메시지 작성 규칙 |
| [exception-handling.md](exception-handling.md) | 예외 계층/던지기/변환/로그 레벨 (원본 문서) |
| [testing.md](testing.md) | 테스트 작성·리뷰 규칙 (원본 문서) |
