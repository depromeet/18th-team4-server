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

## 변수·메서드 이름은 업무 의미로 (자료구조·기계적 동작이 아니라)

지역 변수·메서드·필드 이름은 **그것이 업무적으로 무엇인지**로 짓는다. 자료구조상의 위치(`boundary`, `split`, `head`/`tail`), 기계적 동작(`afterId`, `sumTokenCount`), 수학 약어(`delta`)만 말하는 이름은 피한다 — 그 코드가 어느 업무 흐름의 무엇인지 전달하지 못한다.

| 나쁨 (자료구조·기계적) | 좋음 (업무 의미) |
|---|---|
| `newBoundary` | `lastSummarizedMessageId` (요약한 마지막 메시지 id) |
| `rawTail` | `recentMessages` (최근 원문 대화) |
| `deltaToSummarize` | `messagesToSummarize` (요약 대상 원문) |
| `computeSummarizeSplit` | `findRecentMessagesStartIndex` |
| `sumTokenCountAfterId` | `sumRecentMessageTokens` |

- 판단 기준: **이름만 보고 "이게 업무적으로 무엇인지" 바로 읽히는가?** 안 읽히면 자료구조 이름이다.
- 한 도메인 안에서는 같은 개념을 **한 용어로** 통일한다. 그 도메인의 용어 사전은 각 `docs/domain/*.md` 에 둔다 (예: [domain/ai-chat.md](../domain/ai-chat.md) 의 "용어" 절). 메서드마다 새 단어를 지어내지 않는다.
- 예외: `i`/`j`(반복자), `id`, `DTO`/`JWT` 등 표준·정착 약어. 표준 라이브러리 개념을 그대로 옮긴 이름(예: 스트리밍 `delta`)은 그 맥락에선 유지한다.
