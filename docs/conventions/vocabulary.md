# 어휘 / 용어 작성 규칙 (vocabulary)

> 적용 대상: 이 저장소에서 작성하는 모든 글 — 문서, PR·이슈 본문, 코드 주석, 커밋 메시지, 로그 메시지, 대화.

어휘 규칙의 원본(canonical) 문서. CLAUDE.md 에는 원칙 요약만, PR 스킬·리뷰 프롬프트에는 이 문서로의 링크만 둔다.

## 원칙

- **그 단어만 따로 봤을 때 무엇을 가리키는지 바로 이해되지 않는 용어는 쓰지 않는다.** 쉽게 이해되는 우리말로 풀어 말하거나 쓴다. 추상 영어 jargon 뿐 아니라 `Tier`, `production` 처럼 영어가 아니어도 맥락 없이는 모호한 약칭·번호 라벨도 포함된다.
  - 예: "Tier 1 / Tier 2" → 그 대상을 풀어 서술한다. "production 호출 0" → "실제 서비스 코드(테스트가 아닌 코드)에서 호출하는 곳이 한 군데도 없음".
- 피해야 할 실패 모드는 두 가지다. ① **추상 개념을 영어로 압축한 jargon** 을 그대로 쓰는 것 (아래 첫 표 — 한국어로 풀어 쓴다), ② 반대로 **표준 기술 용어를 임의로 의역하거나 그 글에서만 통하는 조어를 만드는 것** (아래 둘째 표 — 표준어 그대로 쓴다). 둘 다 읽는 사람이 한 번 더 해석하게 만든다.
- **판단 기준: 그 단어만 따로 봤을 때 팀원(개발자)이 바로 이해하는가?** 아니면 풀어 쓴다. 익숙지 않을 수 있는 표준 용어는 처음 등장할 때만 괄호로 한 줄 풀이를 덧붙인다.

## 첫째 표 — 추상 영어 jargon 은 한국어로 풀어 쓴다

| 피할 표현 | 한국어 대체 |
|---|---|
| fail-fast | 즉시 실패 응답 / 호출을 빠르게 끊는다 |
| silent fallback | 빈 결과 대신 다른 응답으로 조용히 바뀜 |
| fire-and-forget | 결과를 기다리지 않고 비동기 실행 / 응답 대기 없이 백그라운드에서 실행 |
| swallow (예외를 swallow) | 예외를 잡아 로그만 남기고 외부로 안 던짐 |
| happy path | 정상 흐름 |
| best-effort | 가능한 범위에서 시도, 실패해도 통과 |
| short-circuit | 조건 만족 시 이후 단계 건너뜀 |
| noop | 아무 일도 안 함 |
| ROI | 비용 대비 효용 / 그만큼의 가치가 없음 |
| SoT (Source of Truth) | 데이터 출처 기준 / 정답을 갖는 곳 |
| stateless | 상태 저장 없이 / 상태를 두지 않고 |
| graceful degradation | 부분 장애 시 점진적 성능 저하 |
| race condition | 동시성 충돌 |
| eventually consistent | 일정 시간 후 데이터가 맞춰짐 |

## 둘째 표 — 표준 기술 용어를 의역·조어로 바꾸지 않는다

독자가 개발자이므로, 널리 통용되는 표준 기술 용어는 표준어 그대로 쓴다. 한국어로 억지 의역하거나(token bucket → "양동이"), 동작을 그 글에서만 통하는 조어로 압축하지(재시도 횟수 미증가 반납 → "무벌점 반납") 않는다.

| 쓰지 말 것 (자작 의역/조어) | 쓸 것 (표준어 + 필요 시 한 줄 풀이) |
|---|---|
| 양동이 | 토큰 버킷(token bucket) |
| 페이서 | rate limiter(호출 속도 제어) |
| 무벌점 반납 | 재시도 횟수를 올리지 않고 큐로 되돌림 |
| owner 펜싱 | 소유권 검증(fencing) — `lock_owner` 토큰 일치 확인 |
| 헛호출 / 헛probe | 불필요한 반복 호출 |

그대로 쓰는 표준 용어 예: `token bucket`, `rate limiter`, `circuit breaker`, `lease`, `fencing`, `backpressure`, `idempotent`, 비관적 락, `SKIP LOCKED`, `exponential backoff`, `TPM/RPM`.

## 그대로 영문을 쓰는 경우

- 코드 식별자·클래스/메서드/패키지 이름: `RestClient`, `JdkClientHttpRequestFactory`, `Adapter`, `Port`, `BookSearchService`
- HTTP/REST 표준 스펙: `GET`, `POST`, `400`, `Bearer Token`, `Retry-After`
- 정착된 약어와 보편 전문 용어: `JWT`, `JPA`, `MVP`, `SSE`, `RPM`, `TPM`, `Trade-off`

## 정리

공통 판단 기준은 "**그 표현을 보고 바로 이해하는가**":

- 추상 영어 숙어(fail-fast, silent fallback, ROI…) → **한국어로 풀어 쓴다** (첫째 표).
- 표준 명사형 기술 용어(token bucket, rate limiter…) → **표준어 유지, 의역·조어 금지** (둘째 표).
- 로그 메시지도 같은 규칙 — 한글 본문 + 영문 기술 용어 유지 (상세는 [logging.md](logging.md)).
