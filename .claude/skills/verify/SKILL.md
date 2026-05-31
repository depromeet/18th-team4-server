---
name: verify
description: 구현 직후 결정론적 체크리스트를 실행해 잔존물을 보고한다. 현재 항목: (1) TODO 탐지, (2) 전체 테스트 통과 여부, (3) domain/presentation 계층 @Value 탐지, (4) Service/Repository 대응 테스트 클래스 존재 여부. 사용자가 "/verify" 로 호출할 때 사용.
---

# `/verify` 스킬

구현 직후 결정론적 명령만으로 잔존물을 탐지하고 결과를 보고한다.
LLM 판단 없이 명령 종료 코드와 grep 결과만으로 판정한다.

## 실행 순서

### 1. TODO 탐지

```bash
grep -rn "TODO" src/main --include="*.java" \
  | grep -v "src/main/java/com/readum/.*/example/"
```

- 결과가 0줄이면: `✅ TODO 없음`
- 1줄 이상이면: 파일경로:줄번호와 내용을 그대로 출력하고 `❌ TODO N건` 보고

### 2. 전체 테스트 통과

```bash
./gradlew test
```

- 종료 코드 0이면: `✅ 테스트 전체 통과`
- 종료 코드 0이 아니면: 실패한 테스트 목록을 출력하고 `❌ 테스트 실패` 보고

### 3. @Value 탐지 (domain/ + presentation/)

```bash
grep -rn "@Value" src/main --include="*.java" \
  | grep -E "src/main/java/com/readum/(domain|presentation)/" \
  | grep -v "src/main/java/com/readum/.*/example/"
```

- 결과가 0줄이면: `✅ @Value 없음`
- 1줄 이상이면: 파일경로:줄번호와 내용을 출력하고 `⚠️ @Value N건` 보고
- `infrastructure/` 는 classpath 리소스 참조 목적이 달라 탐지 대상에서 제외

> 현재 기준선(2026-05-31): `presentation/` 2건 — `AuthController:39`, `UserController:44`.
> 이 2건은 `@ConfigurationProperties` 미이전 상태로 기존 인지된 항목이다. 새로 추가된 건만 주의 깊게 보면 된다.

### 4. Service/Repository 대응 테스트 클래스 존재 여부

```bash
comm -23 \
  <(find src/main/java -name "*Service.java" -o -name "*Repository.java" \
      | grep -v "src/main/java/com/readum/.*/example/" \
      | sed 's|src/main/java/||; s|\.java$||' | sort) \
  <(find src/test/java -name "*ServiceTest.java" -o -name "*RepositoryTest.java" \
      | sed 's|src/test/java/||; s|Test\.java$||' | sort)
```

- 출력이 없으면: `✅ 누락 테스트 없음`
- 1줄 이상이면: 해당 클래스 경로를 출력하고 `❌ 테스트 없는 클래스 N건` 보고

> 현재 기준선(2026-05-31): 3건 누락 — `SummarySearchService`, `BookRepository`, `SummaryRepository`.
> #60(repository 단위 테스트 → RDS 통합 테스트 전환) 진행 중에는 Repository 항목 판정이 흔들릴 수 있으니 주의.

## 결과 보고 형식

```
## /verify 결과

| 항목 | 결과 |
|------|------|
| TODO | ✅ 없음 / ❌ N건 |
| 테스트 | ✅ 전체 통과 / ❌ 실패 |
| @Value | ✅ 없음 / ⚠️ N건 |
| 누락 테스트 | ✅ 없음 / ❌ N건 |

(실패/경고 항목이 있으면 세부 내용을 항목 아래에 나열)
```

## 주의

- 이 스킬은 현재 항목을 확인하는 것만 한다. 자동으로 수정하지 않는다.
- `./gradlew test` 는 전체 테스트를 실행하므로 시간이 걸릴 수 있다.
