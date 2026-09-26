# 어휘 / 용어 작성 규칙 (vocabulary)

> 적용 대상: 이 저장소에서 작성하는 모든 글 — 문서, PR·이슈 본문, 코드 주석, 커밋 메시지, 로그 메시지, 대화.

어휘 규칙의 원본(canonical) 문서. CLAUDE.md 에는 원칙 요약만, PR 스킬·리뷰 프롬프트에는 이 문서로의 링크만 둔다.

## 원칙

- **그 단어만 따로 봤을 때 무엇을 가리키는지 바로 이해되지 않는 용어는 쓰지 않는다.** 쉽게 이해되는 우리말로 풀어 말하거나 쓴다. 영어 조어뿐 아니라 `Tier`, `production` 처럼 영어가 아니어도 맥락 없이는 모호한 약칭·번호 라벨도 포함된다.
  - 예: "Tier 1 / Tier 2" → 그 대상을 풀어 서술한다. "production 호출 0" → "실제 서비스 코드(테스트가 아닌 코드)에서 호출하는 곳이 한 군데도 없음".
- 나누는 기준은 **영어냐 아니냐가 아니라, 개발자 사이에 정착된 용어냐다.**
  - 정착된 표준 용어(`race condition`, `stateless`, `happy path`…)는 **그대로 쓴다** — 억지로 풀어 쓰거나 의역하면 오히려 한 번 더 해석하게 만든다.
  - 은유·합성 조어·모호한 약어(`swallow`, `silent fallback`, `SoT`…)는 **한국어로 풀어 쓴다**.
- **판단 기준: 그 단어만 따로 봤을 때 팀원(개발자)이 바로 이해하는가?** 아니면 풀어 쓴다. 정착된 용어라도 익숙지 않을 수 있으면 처음 등장할 때만 괄호로 한 줄 풀이를 덧붙인다.

## 풀어 쓸 것 — 은유·합성 조어·모호한 약어

| 피할 표현 | 한국어 대체 |
|---|---|
| fail-fast | 즉시 실패 응답 / 호출을 빠르게 끊는다 / (버그의 경우) 즉시 드러내 멈춘다 |
| silent fallback | 빈 결과 대신 다른 응답으로 조용히 바뀜 |
| fire-and-forget | 결과를 기다리지 않고 비동기 실행 / 응답 대기 없이 백그라운드에서 실행 |
| swallow (예외를 swallow) | 예외를 잡아 로그만 남기고 외부로 안 던짐 |
| SoT (Source of Truth) | 데이터 출처 기준 / 정답을 갖는 곳 |
| ROI | 비용 대비 효용 / 그만큼의 가치가 없음 |

공통점: 이름이 개념을 설명하지 않는다(은유이거나, 그 글쓴이 주변에서만 통하는 압축이거나, 여러 뜻으로 읽히는 약어).

## 그대로 쓸 것 — 정착된 개발 용어

널리 통용되는 표준 용어는 영문 그대로 쓴다. 익숙지 않을 수 있는 용어만 첫 등장에 괄호로 한 줄 풀이한다.

- 일반 CS/분산 시스템: `race condition`, `stateless`, `eventually consistent`, `graceful degradation`, `idempotent`, `backpressure`
- 개발 관용어: `happy path`(정상 흐름), `short-circuit`, `noop`, `best-effort`
- 인프라/동시성: `token bucket`, `rate limiter`, `circuit breaker`, `lease`, `fencing`, `exponential backoff`, 비관적 락, `SKIP LOCKED`, `TPM/RPM`
- 코드 식별자·클래스/메서드/패키지 이름: `RestClient`, `JdkClientHttpRequestFactory`, `Adapter`, `Port`, `BookSearchService`
- HTTP/REST 표준 스펙: `GET`, `POST`, `400`, `Bearer Token`, `Retry-After`
- 정착된 약어와 보편 전문 용어: `JWT`, `JPA`, `MVP`, `SSE`, `RPM`, `TPM`, `Trade-off`

> 2026-07-05 조정: `happy path`·`best-effort`·`short-circuit`·`noop`·`stateless`·`graceful degradation`·`race condition`·`eventually consistent` 는 원래 "풀어 쓸 것" 표에 있었으나, 개발자에게 정착된 용어라 그대로 쓰는 쪽이 가독성이 좋다는 판단으로 이 목록으로 옮겼다.

## 표준 용어를 의역·조어로 바꾸지 않는다

반대 방향의 실패 모드. 독자가 개발자이므로 표준 기술 용어를 한국어로 억지 의역하거나(token bucket → "양동이"), 동작을 그 글에서만 통하는 조어로 압축하지(재시도 횟수 미증가 반납 → "무벌점 반납") 않는다.

| 쓰지 말 것 (자작 의역/조어) | 쓸 것 (표준어 + 필요 시 한 줄 풀이) |
|---|---|
| 양동이 | 토큰 버킷(token bucket) |
| 페이서 | rate limiter(호출 속도 제어) |
| 무벌점 반납 | 재시도 횟수를 올리지 않고 큐로 되돌림 |
| owner 펜싱 | 소유권 검증(fencing) — `lock_owner` 토큰 일치 확인 |
| 헛호출 / 헛probe | 불필요한 반복 호출 |

## 정리

공통 판단 기준은 "**그 표현을 보고 바로 이해하는가**":

- 은유·합성 조어·모호한 약어(swallow, silent fallback, SoT…) → **한국어로 풀어 쓴다**.
- 정착된 표준 용어(race condition, happy path, token bucket…) → **영문 그대로, 의역·조어 금지**. 익숙지 않을 수 있으면 첫 등장에 괄호 한 줄 풀이.
- 로그 메시지도 같은 규칙 — 한글 본문 + 영문 기술 용어 유지 (상세는 [logging.md](logging.md)).
