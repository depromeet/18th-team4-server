# 감상문 생성 OpenAI Batch API 전환 — 설계 (Spec 3)

> 입력 노트: [`2026-06-17-summary-batch-pivot-notes.md`](./2026-06-17-summary-batch-pivot-notes.md)
> 이 브랜치(`summary-batch-pivot`)는 동기 호출 방식(Plan 1+2)을 베이스로 떠서, 배치 전환까지 같은 줄기에 이어 커밋해 **PR 하나**로 dev 에 올린다.

---

## 1. 배경 / 문제

매일 새벽 6시 감상문 대상 세션을 한꺼번에 OpenAI 로 동기 호출하던 구조는, 호출이 몰려 429(호출 속도 제한) / quota 초과로 배치가 실패했다. Plan 1+2 가 작업 큐 + 호출 속도 페이싱(bucket4j) + quota 차단기로 현재 규모의 burst 문제를 해결했으나, 다음 한계가 남는다.

- 아웃바운드 호출 제어 복잡도를 우리가 전부 짊어진다 (페이싱·차단기·점유 시한·회수기).
- 실시간 채팅과 동기 API 한도를 공유해, 새벽 배치가 채팅 사용자의 429 를 유발할 수 있다.
- 처리량 병목이 분당 토큰 한도라, 페이싱을 잘해도 전체 시간 = (총 토큰 ÷ 분당 한도). 워커를 늘려도 한도는 그대로다.
- 동기 일반 API 는 Batch API 대비 비싸다.

**전환 방향**: 자동 새벽 감상문 생성을 OpenAI Batch API(비동기 / 별도의 더 큰 호출 한도 풀 / 약 절반 비용 / 24시간 완료 윈도우)로 옮긴다. 수동(데모) 요청만 기존 동기 경로로 남긴다.

---

## 2. 불변 제약

- 세션 : 감상문 = **1:1**, 재요약 없음. 감상문 완료 세션은 `LOCKED` = **영구 종료**(더 이상 대화 불가).
- "생성 중" 은 별도 세션 상태가 아니라 **"유효한 활성 작업(`summary_job`)이 있음"** 으로 표현한다 (별도 `SUMMARIZING` 세션 상태를 두지 않음).
- 수동(데모) 요청은 **즉시성** 경로 → 동기 호출 유지. Batch API 에 태우지 않는다.
- 자동 새벽 요청은 **Batch API** 경로.

---

## 3. 범위 — 현재 PR vs 후속 PR

### 현재 PR 에 포함

동기→배치 전환의 본체. 자동 경로 Batch API 화, 수동 동기 경로 분리, 데이터 모델·상태 확장, 채팅 차단 확장, 실패/충돌/크래시의 **데이터 안전** 보장, Plan 2 삭제.

### 후속 PR 로 분리 (이 문서 §12 에 기록)

다중 builder / 다중 collector 환경의 **정교한 안정성 보강** 5종. 현재 PR 은 더 단순한 정적 방식으로 데이터 안전을 이미 보장하므로, 이 5종은 효율/추적성 개선이며 나중에 별도 PR 로 다룬다.

---

## 4. 데이터 모델

### 4.1 `summary_job` — 실행 경로 구분 칸 추가

`execution_mode { SYNC, BATCH }` 추가.

- `SYNC` = 수동/데모. 동기 워커가 일반 OpenAI API 로 단건 처리.
- `BATCH` = 자동 새벽. builder 가 청크로 묶어 Batch API 제출.

`active_session_id` unique 제약은 **`execution_mode` 와 무관하게** 유지한다 → 한 세션은 어떤 경로든 활성 작업을 동시에 **1개만** 가진다.

### 4.2 `summary_job` 상태 확장

기존 `PENDING / PROCESSING / SUCCEEDED / FAILED` 에 batch 전용 `BATCH_BUILDING`, `SUBMITTED` 추가.

- **SYNC 흐름**: `PENDING → PROCESSING → SUCCEEDED / FAILED`
- **BATCH 흐름**: `PENDING → BATCH_BUILDING → SUBMITTED → SUCCEEDED` (요청별 실패 시 `→ PENDING` 재큐 또는 `→ FAILED`)

상태별 점유(`lock_owner` / `locked_until`) 의미:

| 상태 | 점유 시한 | 누가 들고 있나 | 회수기(reaper) 대상 |
|------|:--------:|----------------|:-------------------:|
| `PROCESSING` (SYNC) | 있음 | 동기 워커 | O (점유 시한 만료 시) |
| `BATCH_BUILDING` | 있음 | builder | O (점유 시한 만료 시) |
| `SUBMITTED` | **없음** | OpenAI 대기 중 | **X** (collector + `openai_batch` 가 관장) |

> `SUBMITTED` 로 전환할 때 `lock_owner` / `locked_until` 은 비운다. 이 작업은 워커가 들고 있는 게 아니라 OpenAI 처리를 기다리는 상태이고, 그 생사는 시간 기반 회수기가 아니라 collector 가 batch 상태를 보고 판단한다.

### 4.3 신규 엔티티 `OpenAiBatch` (테이블 `openai_batch`)

| 칸 | 설명 |
|----|------|
| `batch_id` | OpenAI 가 발급한 batch 식별자 |
| `status` | local 상태: `SUBMITTED → COMPLETED / FAILED` |
| `input_file_id` | 업로드한 입력 파일 id |
| `output_file_id` | 결과 파일 id (완료 후) |
| `error_file_id` | 에러 파일 id (완료 후) |
| `job_count` | 이 batch 에 묶인 작업 수 |
| `created_at` / `updated_at` | 시각 |

- `summary_job` 은 nullable `openai_batch_id` 로 자신이 속한 batch 를 참조(`SUBMITTED` 전환 시 설정).
- **현재 PR 은 OpenAI 제출 성공 후** `openai_batch` 행을 만든다 (local 초기 상태 `SUBMITTED`). 제출 전 `BUILDING` 선생성은 후속 PR(§12-1).

---

## 5. 두 실행 경로

### 5.1 SYNC (수동/데모) — 기존 자산 재사용, 필터만 추가

- **진입**: `SummaryDraftService` → `EnqueueSummaryJobService(execution_mode = SYNC)`. 응답은 기존 **202 + 폴링** 계약 유지.
- **중복 방지**: 같은 세션에 활성 작업이 있으면(`existsByActiveSessionId`) 수동 요청은 **새 작업을 만들지 않고** 409(`SUMMARY_IN_PROGRESS`) 로 거부한다.
- **처리**: 기존 동기 워커(`SummaryGenerationWorker`)가 **`execution_mode = SYNC` 작업만** 선점하도록 필터한다. 일반 OpenAI API 직접 호출.
- **보호 장치**: 호출 속도 limiter·quota 차단기 **없음**(§9 에서 삭제). 수동은 저빈도 데모 경로이므로 **timeout + 단건 실패 분류(기존 4xx/5xx/quota) + 동일 세션 중복 방지** 만 둔다.

### 5.2 BATCH (자동 새벽) — 신규

- **적재**: `SummaryScheduler`(매일 6시) → `EnqueueSummaryJobService(execution_mode = BATCH)`. 대상 쿼리(ACTIVE + 누적 토큰 ≥ 임계값 + 최근 24시간 채팅) 유지.
- **builder** (다중, SKIP LOCKED 로 청크 선점):
  1. `PENDING(BATCH)` 작업을 **토큰 예산 또는 `maxJobsPerBatch` 중 먼저 닿는 데까지** 묶어 `BATCH_BUILDING` 으로 점유.
  2. 각 작업을 JSONL 한 줄로 만든다. `custom_id` = `summaryjob-{jobId}` (결정적 — 결과를 작업에 매핑하는 키).
  3. 입력 파일 업로드 → OpenAI batch 생성 → `batch_id` 수신.
  4. 한 트랜잭션에서 `openai_batch` 행 생성(`SUBMITTED`) + 작업들 `SUBMITTED` 전환 + `openai_batch_id` 연결.
- **collector** (현재 PR 은 **단일 실행**, 주기 폴링):
  1. 비종료 `openai_batch`(local `SUBMITTED`) 에 대해 OpenAI batch 상태 조회.
  2. 진행 중이면 둔다.
  3. `completed` 면 output/error 파일을 내려받아 `custom_id` 로 작업을 찾아 처리(§8.1).
  4. `failed` / `expired` 등 종료 실패면 그 batch 의 미완 작업 전체를 재큐/실패 처리(§8.2), `openai_batch` 를 `FAILED` 로.
- **Port / Adapter**:
  - Port: `domain/summary/out/SummaryBatchClient` — `uploadAndSubmit(chunk)` / `pollStatus(batchId)` / `downloadFile(fileId)`.
  - Adapter: `infrastructure/ai/openai/batch/SummaryBatchClientImpl`.

> 컴포넌트 이름(`SummaryBatchSubmitService`, `SummaryBatchCollectService`, 스케줄러)은 구현 계획 단계에서 CLAUDE.md 네이밍 규칙에 맞춰 확정한다.

---

## 6. 채팅 차단 & 세션 상태 도출 (A안)

### 6.1 정책

세션의 운명(LOCKED)은 builder 가 메시지를 **스냅샷하는 시점**(= `BATCH_BUILDING` 진입)에 확정된다. 그 이후 도착하는 메시지는 어차피 이번 요약에 안 들어가고, 결과 도착 시 세션이 잠기면 유실된다. 따라서 **스냅샷 시점부터 채팅을 차단**하는 것이 정직한 동작이다(A안).

- **차단 적용 지점**: 채팅 메시지 **생성 API** 가 대상 세션을 차단한다(생성 중 세션엔 새 메시지를 받지 않음, `SESSION_LOCKED` 류).
- **차단 시작**: `BATCH_BUILDING` 진입 순간(스냅샷 정합성 보장).
- `PENDING` 은 차단하지 않는다 → 6시 적재 직후~빌드 전의 막판 메시지는 오히려 스냅샷에 포함되는 이득.
- `SUBMITTED` 동안에도 세션을 차단한다. 이는 **의도된 제품 정책**이다: 스냅샷 정합성(요약에 들어간 내용과 잠금 시점의 대화 내용이 일치)을 보장한다. 대가로 Batch API 완료가 지연되면 세션이 최대 24시간 잠길 수 있다 — 이 UX 리스크는 별도 검토 대상(§12-4).

### 6.2 "SUMMARIZING(생성 중)" 도출 확장

세션 목록 status 도출(CASE)과 채팅 생성 가드를 batch 상태까지 확장한다. 세션이 "생성 중(차단)" 인 조건:

```
유효 점유 PROCESSING (SYNC, locked_until > now)
  또는 유효 점유 BATCH_BUILDING (locked_until > now)
  또는 SUBMITTED
```

- `PROCESSING` / `BATCH_BUILDING` 은 점유 시한(`locked_until > now`)으로 고아(점유 만료) 작업을 제외한다.
- `SUBMITTED` 는 점유 시한이 없으므로, 작업이 `SUBMITTED` 인 동안 "생성 중" 으로 본다(생사는 collector 가 정리).
- 세션 `LOCKED` → "SUMMARIZED".

---

## 7. 청킹 & 호출 한도 제어

### 7.1 청킹

- **1차 기준: 토큰 예산.** builder 가 `SummaryTokenEstimator`(글자수 기반 보수적 과대추정, §9 에서 유지)로 작업별 토큰을 추정하며 누적, 청크 토큰 상한에 닿으면 끊는다.
- **안전 상한: `maxJobsPerBatch`.** 토큰이 적은 짧은 대화만 몰려도 한 batch 가 비대해지지 않도록 건수 상한을 둔다. 토큰 상한 / 건수 상한 중 **먼저 닿는 데서** 끊는다.
- 토큰 추정이 과대추정이므로 실제 토큰은 항상 상한 이하 → batch 입력 토큰 한도 거부를 구조적으로 회피.

### 7.2 계정 in-flight 토큰 한도 — 정적 보장 (현재 PR)

OpenAI 계정에는 "대기·처리 중인 모든 batch 의 토큰 총합" 한도가 있다. 현재 PR 은 **런타임 합산·예약 없이 설정값으로 보장**한다:

```
builder 동시성 × 청크 토큰 상한 × 안전계수  ≤  계정 토큰 한도 - 마진
```

이 부등식이 설정상 성립하도록 값을 잡으면, 최악의 동시 제출에도 한도를 넘지 않는다. 런타임 토큰 합산·DB 락 기반 예약(다중 builder race 정밀 제어)은 후속 PR(§12-2).

---

## 8. 실패 · 재시도 · 충돌 · 크래시

### 8.1 요청별 실패 (흔함)

batch 가 "일부 성공 + 일부 실패" 로 완료되는 것은 정상이다(OpenAI 가 요청 1건 단위로 결과/에러를 보고). collector 는:

- **성공분**: `custom_id` → 작업 → 감상문 저장 + 세션 `LOCKED` + 작업 `SUCCEEDED`. (성공분은 다시 보내지 않음)
- **실패분**: 그 작업만 분류 — 재시도 가능(5xx·일시 오류 등)이면 `PENDING` 으로 되돌림(시도 횟수 +1, backoff), 재시도 불가(4xx 류)면 `FAILED`.

재큐된 작업은 다음 builder 가 **새 청크에 다른 세션들과 섞어** 재제출한다(실패분만 모아 같은 batch 를 재전송하는 게 아니다 — OpenAI batch 는 불변이라 재전송 개념 자체가 없다). 이는 Plan 1 의 작업 큐 재시도 기계를 그대로 재사용하는 것으로, 동기 워커가 만들던 결과와 **같은 종류의 결과**를 batch 결과 파일에서 읽어올 뿐이다.

### 8.2 batch 통째 실패 / 만료 (드묾)

입력 파일 오류 또는 24시간 완료 윈도우 초과(`failed` / `expired` 등). 그 batch 의 미완 작업 전체를 재큐(재시도 가능 시) 또는 FAILED 처리하고, `openai_batch` 를 `FAILED` 로 닫는다.

### 8.3 수동 결과 vs 늦게 도착한 자동 결과 충돌 (§5.3)

동시 충돌은 `active_session_id` unique 가 막는다(자동 작업 in-flight 중엔 수동이 새 작업을 못 만듦). 시간차로 "자동 batch 결과가 늦게 도착했는데 세션이 이미 잠긴" 경우를 위해:

- collector 가 저장하는 순간 세션/작업 상태를 확인한다.
- 세션이 아직 `ACTIVE` + 이 작업이 여전히 그 세션의 유효 활성 작업이면 → 감상문 저장 + `LOCKED`.
- 세션이 **이미 `LOCKED`**(다른 경로로 감상문 생성됨) 또는 이 작업이 더는 유효 활성 작업이 아니면 → **자동 결과 폐기**, 작업은 무해하게 종료. (1:1 · 재요약 없음)

이 저장은 **멱등**하다(이미 LOCKED → 폐기, 이미 SUCCEEDED → skip). 그래서 현재 PR 의 단일 collector 가 드물게 중복 실행돼도 DB 는 안전하다(다중 collector 의 명시적 중복 방지는 §12-3).

### 8.4 제출 성공 직후 ~ DB 저장 전 크래시 (§5.4)

OpenAI 가 batch 를 받은 것과 우리가 `batch_id` 를 적는 것은 한 트랜잭션이 아니라 본질적 간극이 있다. 그 사이 크래시 시:

- 작업은 `BATCH_BUILDING` 인 채 점유 시한이 지나 회수기가 `PENDING` 으로 되돌림 → 다음 builder 가 **재제출**(같은 세션이 OpenAI 에 중복 제출될 수 있음).
- 결과적 손해: **드문 고아 batch + 중복 제출 비용**. 데이터 오염은 없다(§8.3 이 늦게 온 결과를 폐기).

현재 PR 은 이 중복을 **허용**한다 — 결정적 `custom_id`(결과 매핑) + 제출 후 `batch_id` 기록만 둔다. 추적성을 높이는 `openai_batch` 선생성·고아 batch 복구는 후속 PR(§12-1).

> 구현 주의: builder 의 점유 시한은 "청크 묶기 + 파일 업로드 + batch 생성" 최악 소요를 넉넉히 넘겨야, 빌드 도중 점유 시한 만료로 다른 builder 가 같은 작업을 또 집는 일이 없다.

---

## 9. 삭제 / 유지 / 폐기

### 삭제 (Plan 2 — 새 설계에서 호출처 0)

자동은 Batch API 가 페이싱을 담당하고, 수동은 저빈도라 보호 장치를 두지 않기로 했으므로 다음은 호출하는 곳이 없어진다. 이 PR 에서 삭제한다.

- `SummaryCallRateLimiter`(+`SummaryCallRateLimiterImpl`)
- `InMemoryAiCallCircuitBreaker`
- 호출 속도 페이싱용 설정/프로퍼티(`SummaryRateLimitProperties` 등)
- 동기 워커의 permit 대기 · requeue(페이싱 사유) 분기

### 유지

- 작업 큐 / 상태·재시도 / 유실 방지, `active_session_id` unique, SKIP LOCKED 선점, 점유 시한 + 회수기.
- `SummaryTokenEstimator` — 청킹에서 재사용.

### 폐기 개념 (있으면 제거, 없으면 부재 확인)

재요약(REFRESH) / `job_type`, `chat_summary_state` 증분 추적(`last_summarized_message_id`), "요약 중에도 채팅 계속" 개념. (세션당 감상문 1개, 빌드 시점 유효 메시지 전체를 한 번 요약)

---

## 10. 설정값 (전부 외부화)

도메인 비즈니스 룰(`domain/summary/config/...Properties`):

- 청크 토큰 상한, `maxJobsPerBatch`, builder 동시성(초기 2), 계정 토큰 한도·안전 마진·안전계수, collector 폴링 주기, batch 완료 윈도우 가정, SYNC timeout, 점유 시한, 재시도 횟수·backoff.

OpenAI 접속 설정(`infrastructure/...Properties`): API 키, 모델, batch 엔드포인트 관련 값.

> 값은 모두 설정이라 운영하며 조정 가능하다. §7.2 의 부등식이 깨지지 않도록 동시성·토큰 상한·마진을 함께 관리한다.

---

## 11. 테스트 전략

### 현재 PR

- **단위**: builder 청킹(토큰 경계 / `maxJobsPerBatch` 경계), collector 결과 매핑(성공 / 요청별 실패 / batch 통째 실패 / §8.3 폐기), SYNC 워커가 `execution_mode = SYNC` 만 선점, 상태 전이.
- **통합**: `execution_mode` 별 큐 선점 격리(SKIP LOCKED), 세션 상태 도출(차단) 쿼리, `openai_batch` 영속.
- **명시 추가**(요청사항 8):
  - `BATCH_BUILDING` 점유 만료 시 회수기가 `PENDING` 으로 회수
  - `SUBMITTED` 는 회수기 회수 대상이 아님
  - 동일 세션에 활성 작업 존재 시 수동 데모 요청이 새 작업을 만들지 않음(409)
  - 채팅 차단 도출 쿼리(`PROCESSING` 유효점유 / `BATCH_BUILDING` 유효점유 / `SUBMITTED` → 차단, `PENDING` → 비차단)
- OpenAI Batch API 는 `SummaryBatchClient` Port 모킹.

---

## 12. 후속 작업 (별도 PR)

다중 builder / collector 환경의 정교한 안정성 보강. 현재 PR 은 데이터 안전을 이미 보장하므로 효율·추적성 개선 성격이다.

1. **`openai_batch` 행 선생성**: OpenAI batch 생성 *전에* `BUILDING` 상태로 행을 먼저 만들고 작업을 연결해, "제출 성공 후 DB 저장 전 크래시" 구간의 추적성을 높인다. (현재: 제출 후 `batch_id` 저장)
2. **계정 in-flight 토큰 back-pressure 정교화**: 다중 builder 가 서로 다른 작업을 선점해도 계정 토큰 예산은 함께 초과할 수 있다(race). 필요 시 DB row lock 기반 토큰 예약 도입. (현재: 동시성 × 청크 토큰 상한 × 마진의 정적 보장)
3. **collector 중복 수집 방지**: collector 가 여럿 돌 때 같은 `openai_batch` 를 동시에 수집할 수 있다. `openai_batch` 수집 단계에 SKIP LOCKED 또는 `COLLECTING` 상태 적용. (현재: 단일 collector + 멱등 저장)
4. **장기 `SUBMITTED` 세션 차단 UX 검증**: `SUBMITTED` 도 생성 중으로 보고 세션을 차단하는 정책은 스냅샷 정합성을 보장하지만, Batch 완료 지연 시 세션이 오래 잠긴다. 제품 정책 검토로 UX 리스크 확인.
5. **보강 테스트**: collector 중복 실행 시 결과 1회만 반영, `custom_id` 매핑 실패 시 무시/로그 처리 등.

---

## 13. 열린 질문 해소 기록

| 노트 §5 질문 | 결정 |
|--------------|------|
| 5.1 채팅 차단 시점 | A안 — `BATCH_BUILDING` 진입(스냅샷) 시점부터 차단, `PENDING` 비차단, `SUBMITTED` 차단 유지(의도된 정책) |
| 5.2 builder 동시성 · 청크 크기 | 토큰 예산 + `maxJobsPerBatch` 안전 상한, 다중 builder 병렬 제출(동시성 초기 2, 설정), 계정 한도는 정적 보장 |
| 5.3 수동 vs 늦은 자동 결과 충돌 | 동시 충돌은 `active_session_id` unique 가 차단. 시간차로 이미 LOCKED 면 자동 결과 폐기(멱등 저장) |
| 5.4 제출~저장 사이 크래시 | 중복 제출 허용(데이터 안전은 §8.3 이 보장). 결정적 `custom_id` + 제출 후 `batch_id` 기록만. 선생성·복구는 후속 PR |
| (토대) 수동/자동 경로 구분 | `summary_job.execution_mode { SYNC, BATCH }`. SYNC=동기 단건, BATCH=배치. Plan 2(limiter·breaker)는 미사용 → 삭제 |
