# 감상문 생성 — 배치 전환 핸드오프 노트

> **목적**: 별도 세션에서 OpenAI Batch API 설계(Spec 3)를 brainstorming 할 때의 입력.
> 핵심만 추렸다. 새 worktree 는 현재 Plan 1+2 브랜치를 베이스로 떠서, 최종적으로 PR 하나(전체 여정 포함)로 dev 에 올린다.

---

## 0. 변하지 않는 제약 (기존·신규 설계 공통)

- 감상문이 완료된 채팅 세션은 **LOCKED = 영구 종료**. 더 이상 대화 불가.
- **세션 : 감상문 = 1:1, 재요약 없음.** (이전 #69 의 "대화 이어가기 + 증분 재요약" 은 폐기됨)
- "생성 중" 은 세션 상태가 아니라 **"유효한 PROCESSING 작업이 있음"** 으로 표현한다 (`SUMMARIZING` 상태를 따로 두지 않음).
- **수동(데모) 요청은 즉시성이 필요** → 동기 경로를 유지한다. Batch API 에 태우지 않는다.

---

## 1. 문제 정의

- 매일 새벽 6시, 감상문 대상 세션을 한꺼번에 조회해 OpenAI 를 호출하는데, 호출이 몰려 **429(rate limit) / quota 초과**로 배치가 실패했다.
- 원인: 옛 스케줄러가 모든 대상 세션을 `CompletableFuture` 로 fan-out 해서, **아웃바운드 호출 속도 제어 없이 + 전체 대화(큰 요청) 그대로** OpenAI 를 직접 호출 → burst.
- 더해서, 작업이 실패하면 **유실**됐다 (복구 수단 없음).

---

## 2. 기존 설계 (현재 워크트리 = Plan 1+2, 구현·테스트 완료)

무엇을 만들었나:

- **종료 모델 전환**: `AiChatSession.Status {ACTIVE, LOCKED}`. 요약 완료 → LOCKED(영구).
- **DB 작업 큐 `summary_job`**: `PENDING → PROCESSING → SUCCEEDED / FAILED`. 작업을 DB 에 영속화해 유실 방지.
  - `active_session_id` unique = 세션당 활성 작업 1개.
  - `SKIP LOCKED` 선점, `lock_owner` 펜싱 토큰, `locked_until` 5분 lease + 회수기(크래시 복구).
- **아웃바운드 호출 페이싱(Plan 2)**: bucket4j RPM/TPM limiter + 토큰 추정기. 분당 허용량 안에서만 호출하고, 제한시간 안에 확보 못 하면 작업을 큐로 되돌린다(실패 아님 — 시도 횟수 증가 없음).
- **quota 차단기(circuit breaker)**: 메모리 기반. quota 소진 시 일정 시간 전체 호출을 멈추고, 서버 재시작 시 자가 회복.
- **실패 분류**: quota → 차단 + 재시도 / 429 burst → Retry-After 재시도 / 4xx → 즉시 FAILED / 5xx·기타 → 재시도.

→ 원래의 **burst 429 문제는 현재 규모에서 해결**됐다.

---

## 3. 기존 설계의 한계

- **제어 복잡도가 우리 쪽에 다 쌓인다**: 워커가 OpenAI 를 동기로 직접 호출하므로 RPM/TPM limiter, permit 대기·requeue, 429 backoff, quota breaker, lease, `lock_owner`, 회수기까지 방어 로직을 전부 우리가 짊어진다.
- **라이브 채팅과 API 한도를 공유한다**: 새벽 배치가 동기 API 예산을 잠식하면, 실시간 채팅 사용자가 429 를 맞을 수 있다.
- **규모 확장의 본질적 한계**: 처리량 병목이 TPM 이라, 페이싱을 아무리 잘 해도 전체 처리 시간 = (총 토큰 ÷ 분당 TPM). 워커를 늘려도 한도는 그대로다.
- **비용**: 동기 일반 API 는 Batch API 대비 비싸다.
- **성격 미스매치**: 감상문 생성은 즉시 응답이 필요 없는 백그라운드 대량 작업인데, 즉시성 모델(동기 호출)로 처리하고 있다.

---

## 4. 새 설계 방향 (OpenAI Batch API)

핵심 전환:

```
기존: 개별 summary_job 을 워커가 OpenAI 에 동기로 직접 호출
신규: 다중 builder 가 job 을 청크로 묶어 Batch API 에 제출 → collector 가 결과 수집
```

Batch API 의 성격: **비동기 / 별도(더 큰) rate limit 풀 / ~50% 저렴 / 24시간 완료 윈도우.**

이점:

1. 아웃바운드 호출 속도 제어 복잡도가 대폭 줄어든다 (페이싱을 제공자가 담당).
2. 라이브 채팅과 API 한도가 분리된다 (Batch 는 별도 풀).
3. 대량 작업을 빠르게 제공자 처리 큐에 올릴 수 있다.
4. 청크 분할로 부분 실패 범위가 작아진다.
5. `custom_id` 로 작업별 결과 매핑·재시도가 명확해진다.

**유지할 것** (Plan 1 에서 그대로 가져감):

- DB 작업 큐(상태/재시도/유실 방지), `active_session_id` unique, `SKIP LOCKED` 청크 선점, lease + 회수기, 토큰 추정기.
- **수동 경로의 동기 호출 + Plan 2 의 limiter·breaker 재사용** (수동은 Batch 에 안 태우므로 동기 보호 장치가 계속 쓰인다 — Plan 2 가 버려지는 게 아님).

**새로 필요한 것**:

- `summary_job` 상태 확장: `BATCH_BUILDING`, `SUBMITTED` 추가.
- `openai_batch` 테이블 (batch id / 상태 / 파일 id / job 수).
- builder(청크 선점 → JSONL 생성 → 제출 → SUBMITTED), collector(상태 폴링 → 결과 파일 다운로드 → `custom_id` 매핑 → 감상문 저장 + 세션 LOCKED).

**버릴 것** (옛 초안이 재요약 가능 기준으로 쓰여 들어간 부분 — 재요약 불가/LOCKED 에 맞춰 전부 제거):

- 재요약(REFRESH) 대상·`job_type`.
- `chat_summary_state` 증분 추적(`last_summarized_message_id`), "다음 재요약 대상" 개념.
- "요약 중에도 채팅 계속 / `SUMMARIZING` 으로 안 잠금".
- → 세션당 감상문 1개, build 시점의 유효 메시지 전체를 한 번 요약한다.

---

## 5. brainstorming 에서 정할 열린 질문

- batch 가 SUBMITTED 되어 결과를 **최대 24시간 기다리는 동안 채팅을 언제부터 차단**할지. (동기 모델은 "생성 시작 시 차단" 이었는데, batch 는 제출~완료 간극이 크다. 제출 시점 차단은 너무 이르고, 완료 시점 차단은 그 사이 대화가 요약에 안 들어간다 — 정책 결정 필요.)
- builder 동시성 초기값, 청크 크기.
- 수동 결과와 늦게 도착한 자동 batch 결과의 충돌 처리 (세션이 이미 LOCKED 면 자동 결과는 폐기).
- batch 제출 성공 직후 ~ DB 에 batch id 저장 전 서버 장애 구간(완전 제거 불가)을 어떻게 좁히고 복구할지.
