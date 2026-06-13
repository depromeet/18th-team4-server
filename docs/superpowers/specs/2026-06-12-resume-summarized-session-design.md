# 설계: 독후감 생성 완료 후 대화 이어가기

- 이슈: [#69 사용자는 독후감이 생성이 완료된 세션의 대화를 이어나갈 수 있다](https://github.com/depromeet/18th-team4-server/issues/69)
- 작성일: 2026-06-12
- 상태: 설계 확정 (구현 전)

## 요구사항

1. 독후감 생성이 완료된 채팅 세션에서 사용자가 대화를 다시 이어나갈 수 있다.
2. 대화를 이어간 뒤 독후감을 다시 생성할 수 있다.
3. 과거 독후감은 DB 에 이력으로 유지한다 (향후 독후감 history 기능 + 생성 품질 감사 목적).

## 현재 구조와 문제

- 독후감 생성을 요청하면 `SummaryDraftService` 가 세션을 `CLOSED` 로 바꾸고, `Summary` 를 `IN_PROGRESS` 상태로 미리 생성한 뒤 비동기로 LLM 을 호출한다.
- `CLOSED` 세션에는 메시지 전송이 차단된다 (`AiChatMessagePersistService.loadHistory()` 의 `SESSION_CLOSED` 예외). 독후감 재생성 요청도 `SESSION_ALREADY_CLOSED` 로 차단된다.
- `Summary` 와 세션은 1:1 관계 (`ai_chat_session_id` 에 unique 제약). 한 번 생성되면 재생성 경로가 없다.
- 생성이 실패한 세션은 `CLOSED` 로 남아 재시도도 대화 재개도 불가능한 채 영구히 막힌다 (기존 결함 — 이번 변경으로 함께 해소).

## 확정된 설계 결정

### 1. 세션 상태: `CLOSED` → `LOCKED` 로 이름 변경, 자동 복귀

별도의 "세션 재개" API 를 만들지 않는다. 세션 잠금은 독후감 생성이 도는 동안에만 유지되고, 생성이 끝나면(성공이든 실패든) 시스템이 자동으로 `ACTIVE` 로 되돌린다.

```
ACTIVE ──(독후감 생성 요청)──> LOCKED ──(생성 성공/실패)──> ACTIVE
```

- `AiChatSession.Status` = `{ACTIVE, LOCKED}`. `CLOSED` 라는 이름은 "영구 종료" 로 읽히므로, "생성이 도는 동안 잠깐 잠김" 을 뜻하는 `LOCKED` 로 바꾼다.
- 엔티티 메서드: `close()` → `lock()`, `isClosed()` → `isLocked()`, 복귀용 `unlock()` 추가.
- 프론트는 새로 호출할 것이 없다. 생성 완료 후 그냥 메시지를 보내면 된다.

검토 후 채택하지 않은 안:
- 명시적 재개 API (`POST .../reopen`): 잠금이 생성 작업 동안에만 유지되는 모델에서는 불필요.
- 메시지 전송 시 암묵적 재개: 상태 전이가 부수효과로 숨고, 전송 실패 시 어중간한 상태가 생김.
- 영구 종료 상태 예약: 토큰 제한은 유저별 하루 한도(요청 차단)이지 세션을 영구히 닫는 개념이 아니므로 불필요.

### 2. `Summary`: 세션과 1:N, 끝난 시도의 불변 기록

- `summary.ai_chat_session_id` 의 unique 제약을 제거하고 세션당 여러 건을 허용한다. 재생성할 때마다 새 행이 추가되고, **"현재 독후감" = 가장 최근 행**이다.
- 과거 행이 그대로 남아 이력 보존 요구(3)가 테이블 구조 자체로 충족된다. 재생성이 실패해도 직전 COMPLETED 행이 보존된다.
- `Summary.Status` = `{COMPLETED, FAILED}`. **`IN_PROGRESS` 를 제거한다.** Summary 행은 생성 시도가 끝난 시점에 결과(성공이면 내용 포함, 실패면 실패 기록)와 함께 한 번만 생성되고 이후 불변이다. `complete()`/`fail()` 같은 사후 변경 메서드는 삭제한다.
- "생성 중" 상태는 Summary 가 아니라 세션의 `LOCKED` 가 표현한다. 기존 `IN_PROGRESS` 행이 하던 두 역할은 모두 세션 쪽으로 옮겨진다:
  - 폴링하는 프론트에 "생성 중" 알리기 → 세션 `LOCKED` 로 판정
  - 중복 생성 방지 → 비관적 락 + "ACTIVE 일 때만 LOCKED 전이"
- FAILED 행을 남기는 이유: 세션은 실패 후 ACTIVE 로 복귀하므로 세션만 봐서는 실패를 알 수 없다. 폴링 응답("생성 실패")의 영속 신호이자 품질 감사 기록으로 FAILED 행이 필요하다.

검토 후 채택하지 않은 안:
- 1:1 유지 + 별도 이력 테이블: 기존 조회 경로는 안 바뀌지만, 재생성 실패 시 직전 독후감을 잃고, 완성된 Summary 를 덮어쓰는 상태 전이가 필요해짐.
- outbox 패턴 (작업 행 + 스캔 워커 + 재시도): 서버가 죽어도 생성이 재개되는 내구성을 얻지만, MVP 단계의 독후감 생성 한 건에 들일 기계장치가 아님. 별도 이슈로 미룬다.

### 3. 독후감 생성 흐름 (`SummaryDraftService`)

- **TX1 (동기)**: 세션을 비관적 락으로 조회 → 자격 검증(ACTIVE + 누적 토큰 ≥ 500) → `session.lock()` → 대화 이력 로드 → 202 반환. Summary 행을 미리 만들지 않는다.
- **비동기**: LLM 호출.
- **TX2 (완료)**: 성공 시 COMPLETED Summary 행 생성 + `session.unlock()` 을 한 트랜잭션으로 수행. 실패 시 FAILED 행 생성 + `unlock()` 을 마찬가지로 한 트랜잭션으로 수행.

### 4. 재생성 자격: 추가 규칙 없음

- 기존 자격(세션 ACTIVE + 누적 토큰 ≥ 500)을 그대로 쓴다. 생성 완료 후 곧바로(새 대화 없이) 다시 요청해도 허용한다 — "같은 대화로 다시 뽑기" 를 의도적으로 허용.
- 남용(반복 재생성으로 LLM 비용 증가)이 실제 문제가 되면 그때 별도 정책으로 다룬다. 동시 중복 생성은 비관적 락이 이미 막는다.

### 5. API 동작 변화

| API | 변화 |
|---|---|
| `POST /api/v1/ai-chat/sessions/{sessionId}/messages` | 차단 조건이 `isLocked()` 로 바뀜. LOCKED 면 400 `SESSION_LOCKED` ("독후감 생성 중에는 메시지를 보낼 수 없습니다"). 생성이 끝난 세션은 ACTIVE 이므로 그대로 전송 가능 — 이슈의 본체 |
| `GET .../summary` | 세션 LOCKED → 409 "생성 중". 그 외에는 최신 Summary 행 기준: FAILED → 409 "생성 실패", COMPLETED → 200, 행 없음 → 404. 폴링 계약(생성 요청 → 폴링 → 완료/실패 확인)은 첫 생성이든 재생성이든 동일 |
| `POST .../summary-draft` | 재생성도 같은 엔드포인트 재사용 (신규 API 없음). LOCKED 세션이면 409 — 기존 `SESSION_ALREADY_CLOSED` ("이미 감상문이 작성된 세션입니다") 대신 기존 코드 `SUMMARY_IN_PROGRESS` ("감상문을 생성 중입니다") 를 재사용 |
| `GET .../summary-draft/eligibility` | `IneligibleReason.SESSION_ALREADY_CLOSED` → `SUMMARY_IN_PROGRESS` 로 변경. 응답의 reason 문자열이 바뀌므로 프론트 조율 대상 |
| `GET /api/v1/ai-chat/sessions` (목록) | 표시 상태 `AiChatSessionDisplayStatus` 의 `CLOSED` 를 `SUMMARIZED` 로 이름 변경 (프론트가 함께 수정해야 하는 변경). 산출 규칙은 아래 표 |

표시 상태 산출 (목록 JPQL — 세션당 최신 Summary 한 건만 join 하도록 수정):

| 상황 | 표시 상태 |
|---|---|
| 세션 LOCKED (생성 중) | `SUMMARIZING` |
| 세션 ACTIVE + 독후감 없음 | `ACTIVE` |
| 세션 ACTIVE + 최신 독후감 COMPLETED | `SUMMARIZED` |
| 세션 ACTIVE + 최신 독후감 FAILED | `FAILED` |

`CLOSED` 라는 표시 값을 유지하지 않는 이유: 새 모델에서 그 세션은 대화를 이어갈 수 있으므로 "닫힘" 이라는 이름이 실제 의미와 어긋난다. 이번 기능 자체가 프론트 수정(독후감 완료 후 입력창 활성화 등)을 동반하므로 그 김에 함께 바꾼다.

### 6. 에러 코드 변화 (`AiChatErrorCode`)

| 기존 | 변경 |
|---|---|
| `SESSION_CLOSED` ("종료된 세션에는 메시지를 보낼 수 없습니다.") | `SESSION_LOCKED` ("독후감 생성 중에는 메시지를 보낼 수 없습니다.") |
| `SESSION_ALREADY_CLOSED` ("이미 감상문이 작성된 세션입니다.") | 삭제 — LOCKED 세션에 대한 생성 요청 차단은 `SUMMARY_IN_PROGRESS` 를 재사용 |
| `SUMMARY_IN_PROGRESS`, `SUMMARY_GENERATION_FAILED`, `SUMMARY_NOT_FOUND` | 유지 (판정 근거만 "세션 LOCKED / 최신 행" 기준으로 바뀜) |

### 7. 데이터 마이그레이션 (dev RDS)

1. `summary.ai_chat_session_id` 의 unique 제약 제거
2. 기존 `IN_PROGRESS` Summary 행 → `FAILED` (생성 도중 서버가 죽으며 남은 고착 행)
3. 기존 `CLOSED` 세션 → 전부 `ACTIVE` (구 모델의 "종료된" 세션이 새 모델에서는 대화 가능 세션)

적용 방식(수동 SQL vs 마이그레이션 도구)은 현재 프로젝트의 스키마 관리 방식을 확인해 구현 계획 단계에서 확정한다.

### 8. 알려진 한계 (이번 이슈 범위 밖)

- 생성 도중 서버가 죽으면 세션이 `LOCKED` 로 남아 사용자가 풀 수 없다. 현재 코드도 동일한 문제(IN_PROGRESS + CLOSED 영구 고착)를 갖고 있어 이번 변경으로 나빠지는 것은 없다. 복구 정책(잠금 시간 초과 해제, outbox 도입 등)은 별도 이슈로 다룬다.
- 유저별 하루 토큰 한도는 이 설계와 무관한 별개 기능이다.

## 영향 범위 (주요 파일)

- `model/aiChat/entity/AiChatSession.java` — Status 이름 변경, `lock()`/`unlock()`/`isLocked()`
- `model/summary/entity/Summary.java` — Status 축소, 불변화(사후 변경 메서드 삭제), 생성 팩토리 정리
- `model/summary/repository/SummaryRepository.java` — 최신 행 조회 (`findTopBy...OrderByIdDesc` 계열 derived query)
- `model/aiChat/repository/AiChatSessionRepository.java` — 목록 JPQL 의 표시 상태 CASE + 최신 Summary join
- `domain/aiChat/service/SummaryDraftService.java` — TX 구조 변경 (위 3절)
- `domain/aiChat/service/policy/SummaryDraftPolicy.java` — LOCKED 분기, IneligibleReason 이름 변경
- `domain/aiChat/service/AiChatMessagePersistService.java` — `isLocked()` 검증 + 에러 코드 교체
- `domain/aiChat/service/SummarySearchService.java` — 세션 LOCKED / 최신 행 기준 판정
- `domain/aiChat/dto/AiChatSessionDisplayStatus.java` — `CLOSED` → `SUMMARIZED`
- `domain/aiChat/exception/AiChatErrorCode.java` — 6절의 코드 변경
- `presentation/controller/aiChat/AiChatController.java` — Swagger 설명 갱신
- 관련 테스트·픽스처 전반 (CLOSED/IN_PROGRESS 전제 수정)

## 테스트 계획

컨벤션대로 서비스 단위 테스트 + 조회 DAO 통합 테스트. 핵심 시나리오:

- 독후감 생성 성공 시 세션이 ACTIVE 로 복귀하고 COMPLETED 행이 추가된다
- 독후감 생성 실패 시 세션이 ACTIVE 로 복귀하고 FAILED 행이 추가된다
- 복귀한 세션에 메시지를 보낼 수 있다
- LOCKED 세션에는 메시지 전송이 400 으로 차단된다
- LOCKED 세션에 독후감 생성을 요청하면 409 로 차단된다
- 재생성하면 새 행이 추가되고 기존 행이 보존된다 (최신 행이 "현재 독후감")
- GET /summary: LOCKED → 409 생성 중 / 최신 FAILED → 409 실패 / 최신 COMPLETED → 200 / 없음 → 404
- 목록 표시 상태 4종 (SUMMARIZING / ACTIVE / SUMMARIZED / FAILED) 산출 — DAO 통합 테스트
- 재생성 중(LOCKED)인 세션의 표시 상태는 직전 COMPLETED 가 있어도 SUMMARIZING 이다

## 프론트 조율 사항

- 표시 상태 값 `CLOSED` → `SUMMARIZED` 변경
- eligibility 응답의 reason 문자열 변경
- 독후감 완료 후에도 대화 입력이 가능해지는 화면 흐름
