# 이름 규칙

> 적용 대상: 클래스/DTO/Port/Repository 등에 이름을 붙일 때.

| 종류 | 규칙 | 예시 |
|------|------|------|
| Command 서비스 | `{Action}Service` | `SignUpService` |
| Query 서비스 | `{Domain}SearchService` | `UserSearchService` |
| 트랜잭션 경계 협력자 빈 | `{동작대상}Writer`(쓰기) / `{동작대상}Reader`(읽기), package-private | `UserBookRegistrationWriter`, `UserBookConflictReader` |
| Command DTO | `{Action}Command` | `SignUpCommand` |
| Result DTO | `{Action}Result` / `{Domain}Result` | `SignUpResult`, `ExampleResult` |
| Request DTO | `{Action}Request` | `ExampleCreateRequest` |
| Response DTO | `{Domain}Response` | `ExampleResponse` |
| Port (out) | `{Domain}{Action}Client` | `ExampleSearchClient` |
| Adapter (infra) | `{Port}Impl` | `ExampleSearchClientImpl` |
| Controller | `{Domain}Controller` | `ExampleController` |
| Entity | 도메인명 그대로 | `User`, `ExampleEntity` |
| Repository | `{Entity}Repository` | `UserRepository` |
| ErrorCode | `{Domain}ErrorCode` | `AuthErrorCode` |
| Exception (공용) | `{HttpStatus}Exception` | `UnauthorizedException`, `NotFoundException` |

> 어휘·용어 작성 규칙(문서/PR/주석 공통)의 상세 표는 [vocabulary.md](vocabulary.md) 참조.
