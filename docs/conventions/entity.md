# 엔티티 규칙

> 적용 대상: JPA 엔티티를 만들 때, 그 엔티티의 테스트 Fixture 를 만들 때.

- Lombok: `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, `@AllArgsConstructor(access = PRIVATE)`. 단, 같은 패키지(`src/test`)의 `{Entity}Fixture` 가 전체필드 생성자를 컴파일-안전하게 호출해야 하면 그 엔티티만 `@AllArgsConstructor(access = PACKAGE)` 로 둔다 (리플렉션 대신 생성자 직접 호출). 이 완화는 같은 패키지에 실제 서비스 코드 협력자가 없는 entity 패키지 한정으로 적용한다.
- 정적 팩토리 메서드: 엔티티는 불변식을 강제하는 도메인 팩토리(`create()` 계열 — `create()`/`createInProgress()`/`createChild()` 등)만 노출한다. 특정 id 지정·임의 상태(모든 필드 지정) 객체 생성은 **테스트 전용 책임**이며, 엔티티가 아니라 그 엔티티와 같은 패키지의 `src/test` `{Entity}Fixture` 헬퍼가 package-private 전체필드 생성자를 호출해 수행한다 (`@com.readum.support.TestOnly` 표식). 운영 엔티티에 `of()` (전체 필드 지정 생성) 를 두지 않는다.
- 픽스처는 **의도가 드러나는 명명 팩토리만** 노출한다 (예: `persistedUser`/`activeToken`/`persistedClosedSession`). 운영 DB 에 존재할 수 있는 유효 상태만, 호출부마다 실제로 달라지는 인자만 받고 나머지는 내부 기본값. 범용 `of()`/`create()` 같은 모든 필드 받는 탈출구와 `ReflectionTestUtils` 는 쓰지 않는다.
- setter 없이 불변 지향
