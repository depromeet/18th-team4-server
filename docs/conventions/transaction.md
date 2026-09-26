# 트랜잭션 규칙

> 적용 대상: 서비스에 트랜잭션 경계를 그을 때 — `@Transactional(readOnly = true)` 부착 여부, 그리고 외부 I/O 를 트랜잭션 밖으로 뺄지 정할 때.

## `@Transactional(readOnly = true)` 부착 기준

- **단순 조회 service 에는 `@Transactional(readOnly = true)` 를 붙이지 않는다.** Spring Data JPA 의 `SimpleJpaRepository` 가 클래스 레벨에서 이미 readOnly 트랜잭션을 감싸므로, service 가 단일 Repository 호출만 하면 효과가 중복된다. 다른 Query 서비스 (`UserSearchService`, `AiChatMessageSearchService`, `AiChatHistorySearchService`) 도 이 컨벤션으로 통일.
- 명시적으로 `@Transactional(readOnly = true)` 를 붙이는 예외 케이스 — 효과가 실제로 나타날 때만:
  - 한 service 메서드에서 여러 Repository 호출을 한 트랜잭션으로 묶어 일관된 상태로 읽어야 할 때
  - OSIV 가 꺼진 환경에서 service 의 변환 단계가 LAZY 컬렉션을 접근해야 할 때

## 외부 I/O 는 트랜잭션 밖에서 — 트랜잭션 경계 협력자 빈

- **외부 HTTP 호출(알라딘·LLM 등)을 `@Transactional` 구간 안에서 하지 않는다.** 트랜잭션이 열리는 동안 DB 커넥션이 점유되므로, 외부 응답을 기다리는 사이 커넥션이 낭비되고 요청이 몰리면 풀이 고갈된다.
- **"외부 호출 + 원자적 DB 쓰기" 가 한 흐름에 섞이면**, 외부 호출은 트랜잭션 밖(서비스 본문)에서 하고, 원자적으로 묶여야 하는 DB 쓰기만 별도 협력자 빈의 `@Transactional` 메서드에 위임한다.
- **메서드 분리가 아니라 클래스(빈) 분리로 한다.** `@Transactional` 은 프록시 기반이라 같은 클래스 안의 `this.method()` 호출은 프록시를 거치지 않아 트랜잭션이 열리지 않는다(self-invocation). 트랜잭션 구간은 반드시 다른 빈으로 뺀다.
- **`TransactionTemplate` 을 쓰지 않는다.** 트랜잭션 경계는 선언적 `@Transactional` 로만 드러낸다.

### 협력자 빈을 만들지 않는 경우

- 외부 I/O 없이 DB 만 만지는 서비스 → 서비스 메서드에 직접 `@Transactional`. 협력자 빈을 만들지 않는다.
- 외부 I/O 는 있으나 DB 쓰기가 없거나, 쓰기들이 서로 독립적이라 원자성이 필요 없을 때 → 트랜잭션 자체가 불필요하므로 나누지 않는다.

### 이름·배치

- 이름: `{동작대상}Writer`(쓰기) / `{동작대상}Reader`(읽기).
- 가시성: package-private (`class`) 로 둔다. 유스케이스 진입점은 여전히 `{Action}Service` 하나이며, 협력자 빈은 그 기능 폴더 안의 내부 부품으로 가둔다.
- 묶음 단위: 메서드마다 빈을 만들지 말고, 한 기능의 관련 DB 쓰기를 한 협력자 빈에 메서드로 모은다.
