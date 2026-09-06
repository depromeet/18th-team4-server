# DTO 규칙

> 적용 대상: 계층 간에 데이터를 넘기는 DTO(record)를 만들 때, Entity → Result 변환을 어디에 둘지 정할 때.

## 기본 규칙

- 파라미터 3개 이상이면 DTO(record) 사용
- **presentation DTO**: Request (`toCommand()` 포함), Response (`from(Result)` 포함)
- **domain DTO**: Command, Result
- 계층 간 변환 책임은 presentation DTO 가 담당

## Entity → Result DTO 변환 책임

기본 규칙: **Entity → Result DTO 변환은 DTO 의 `from(Entity, ...)` 정적 팩토리로 수행한다.** Service 가 비즈니스 흐름에 집중하도록 변환 잡일을 DTO 로 위임한다.

```java
// ✅ DTO 가 변환 책임 보유
public record UserBookCreateResult(...) {
    public static UserBookCreateResult from(UserBook userBook, Book book) { ... }
}

// service 는 흐름만 표현
return UserBookCreateResult.from(saved, book);
```

```java
// ❌ service 안의 private toResult() 헬퍼 또는 inline new XxxResult(...)
private UserBookCreateResult toResult(UserBook userBook, Book book) { ... }
return new UserBookCreateResult(saved.getId(), saved.getUserId(), ...);
```

- **여러 데이터 출처를 합치는 경우도 정적 팩토리에서 처리**: 여러 데이터 출처(여러 entity 또는 entity + 외부 인자) 를 합치는 경우 `from(A, B, ...)` 시그니처로 표현. 두 entity 에 의존하는 게 어색해 보일 수 있지만, service 안에서 변환하는 것과 비교하면 결합 정도는 동일하고 service 가 가벼워진다. 예: `UserBookCreateResult.from(UserBook, Book)`, `UserSessionInfoResult.from(User, boolean hasRegisteredBooks)`.
- **Wrapper DTO (List + 페이지 메타 같은 단순 합성) 는 `from()` 두지 않고 service 에서 직접 생성한다.** 진짜 entity → DTO 변환 책임은 안에 들어가는 element DTO (예: `MessageResult.from(AiChatMessage)`) 가 갖는다. wrapper 의 `from(Slice, ...)` 같은 시그니처는 domain DTO 를 Spring Data 같은 인프라 타입에 결합시키므로 지양한다 — `Slice.hasNext()` 풀어내기는 service 가 책임지고, DTO 는 plain types (List, int, boolean) 만 알도록 둔다.

## Service 안에 변환을 두는 예외 케이스

다음 상황에만 service 안 변환(private 메서드 또는 inline 생성)을 허용:

- 변환에 service 의 다른 의존성 호출이 필요할 때 (예: 권한 체크 결과를 합쳐 리턴)
- 변환 결과가 호출자별로 달라질 때 (예: 권한에 따라 일부 필드 마스킹)
- 변환이 service 의 트랜잭션 안에서 LAZY 컬렉션을 만져야 할 때

> 단순히 "외부에서 받은 인자(예: hasRegisteredBooks 같은 boolean) 가 entity 외에 추가로 필요" 한 경우는 예외에 해당하지 않는다 — `from(Entity, extraArg)` 시그니처로 충분히 표현 가능하다.
