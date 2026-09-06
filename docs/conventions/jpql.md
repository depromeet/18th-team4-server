# JPQL / 쿼리 규칙

> 적용 대상: Repository 쿼리 메서드를 작성할 때 (derived query, `@Query`, `@NativeQuery`).

- **repository 는 기본적으로 자기 엔티티만 반환한다**: 다른 엔티티의 컬럼까지 끌어오는 여러-엔티티 join projection (`select new ...Projection(summary.id, ..., book.title)`) 은 repository 를 타 엔티티의 스키마에 결합시켜 의존성 분리를 무너뜨리므로 기본적으로 쓰지 않는다. 여러 엔티티의 정보가 함께 필요하면 service 가 각 repository 를 호출해 조합하고, `Result.from(A, b필드)` 정적 팩토리로 변환한다. 이때 여러 Repository 호출을 한 트랜잭션으로 묶어 읽게 되면 `@Transactional(readOnly = true)` 를 명시한다 (transaction.md 의 예외 조항).
  - **예외 — join projection 을 허용하는 경우**: 쿼리 조건·정렬·페이지네이션이 여러 엔티티에 걸쳐 있어 DB 가 join 하지 않으면 정확한 결과를 만들 수 없는 조회 (예: "책 제목으로 검색해 감상문을 페이징" — 리스트를 따로 가져와 메모리에서 조합하면 페이징이 깨짐). 다른 엔티티가 표시용 필드만 제공하는 경우는 예외에 해당하지 않는다.- **derived query method 우선**: Spring Data JPA 의 메서드 이름 기반 쿼리(`findBy...And...In...OrderBy...`) 로 표현 가능하면 `@Query` 보다 우선 사용한다. JPQL 본문의 enum 전체 경로 이름/JPQL 문법 잡음을 피하고 타입 안전성을 얻는다. 조건이 4~5개 이상으로 메서드명이 흉해지면 그때 `@Query` 로 전환.
  ```java
  // ✅ derived (단순 조건)
  List<AiChatMessage> findBySessionIdAndStatusAndRoleInOrderByCreatedAtDescIdDesc(
          Long sessionId, AiChatMessage.Status status, Collection<AiChatMessage.Role> roles, Pageable pageable);

  // ✅ default 메서드로 호출자 시그니처 짧게 유지
  default List<AiChatMessage> findRecentForContextWindow(Long sessionId, Pageable pageable) {
      return findBySessionIdAndStatusAndRoleInOrderByCreatedAtDescIdDesc(
              sessionId, AiChatMessage.Status.COMPLETED,
              List.of(AiChatMessage.Role.USER, AiChatMessage.Role.ASSISTANT), pageable);
  }
  ```
- **엔티티 별칭(alias)**: 단일 문자 약어(`r`, `u`, `t`) 금지. 엔티티 이름을 camelCase 로 풀어 쓴다.
  ```java
  // ❌ update RefreshToken r set r.rotatedAt = :now where r.id = :id
  // ✅ update RefreshToken refreshToken
  //       set refreshToken.rotatedAt = :now
  //     where refreshToken.id = :id
  ```
- **쉼표 위치(leading comma)**: 여러 줄 JPQL 에서 쉼표는 **다음 줄 앞단**에 둔다. 컬럼/필드 정렬이 유지되고 라인 추가 시 diff 가 깨끗함.
  ```java
  // ❌ set refreshToken.a = :x,
  //        refreshToken.b = :y
  // ✅ set refreshToken.a = :x
  //      , refreshToken.b = :y
  ```
- leading comma 규칙은 `@Query` / `@NativeQuery` 텍스트 블럭 내부에 한정한다. Java 메서드 파라미터·인자 리스트·배열 리터럴 등은 기존대로 trailing comma 를 사용.
