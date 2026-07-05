# 트랜잭션 규칙

> 적용 대상: 조회/변경 서비스 메서드에 `@Transactional(readOnly = true)` 를 붙일지 정할 때.

- **단순 조회 service 에는 `@Transactional(readOnly = true)` 를 붙이지 않는다.** Spring Data JPA 의 `SimpleJpaRepository` 가 클래스 레벨에서 이미 readOnly 트랜잭션을 감싸므로, service 가 단일 Repository 호출만 하면 효과가 중복된다. 다른 Query 서비스 (`UserSearchService`, `AiChatMessageSearchService`, `AiChatHistorySearchService`) 도 이 컨벤션으로 통일.
- 명시적으로 `@Transactional(readOnly = true)` 를 붙이는 예외 케이스 — 효과가 실제로 나타날 때만:
  - 한 service 메서드에서 여러 Repository 호출을 한 트랜잭션으로 묶어 일관된 상태로 읽어야 할 때
  - OSIV 가 꺼진 환경에서 service 의 변환 단계가 LAZY 컬렉션을 접근해야 할 때
