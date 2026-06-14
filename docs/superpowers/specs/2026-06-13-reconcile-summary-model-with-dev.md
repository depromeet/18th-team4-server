# 설계: dev 의 스케줄러·편집·기록 기능과 감상문 모델 통합

- 이슈: [#69 사용자는 독후감이 생성이 완료된 세션의 대화를 이어나갈 수 있다](https://github.com/depromeet/18th-team4-server/issues/69)
- 작성일: 2026-06-13
- 상태: 설계 확정 (재작업 전)
- 선행 문서: [2026-06-12-resume-summarized-session-design.md](2026-06-12-resume-summarized-session-design.md) — 이 문서가 일부 결정을 갱신/대체한다.

## 배경: 왜 다시 설계하나

선행 설계(#69)는 당시 dev 를 기준으로 잡았다. 그 뒤 dev 에 두 개의 PR 이 병합되면서 감상문 영역이 다른 방향으로 커졌다.

- **PR #73 (자동 생성 스케줄러)**: 매일 오전 6시에 조건을 만족하는 ACTIVE 세션의 감상문을 자동 생성한다. 10분마다 실패한 감상문을 재시도한다. Summary 에 `summaryDate`(날짜) 와 `retryCount`(재시도 횟수) 컬럼, `(ai_chat_session_id, summary_date)` 유니크 제약을 추가했다.
- **PR #80 (감상문 편집)**: 사용자가 완성된 감상문의 제목·본문을 직접 고치는 API. `Summary.edit()` 가 행을 제자리에서 수정한다.
- **감상 기록 목록 (history)**: 사용자 본인의 "종료(CLOSED)된 세션의 완성(COMPLETED)된 감상문" 을 최신순으로 보여준다.

이 기능들은 모두 dev 의 옛 모델 — **"생성 시작 시 IN_PROGRESS 행을 미리 만들고, 성공/실패에 따라 그 행을 고치는" 가변 모델** + **세션을 영구히 `CLOSED` 로 닫는 모델** — 위에 지어졌다. #69 가 도입하려는 모델(세션은 생성 중에만 `LOCKED`, 감상문은 write-once 불변 기록)과 정면으로 충돌한다. 그래서 두 갈래를 그냥 git 으로 합치면 의미가 깨진다. 통합 모델을 정하고 dev 쪽 세 기능을 그 위로 다시 맞춘다.

## 통합 모델의 두 원칙

1. **세션 상태 = `{ACTIVE, LOCKED}`.** `CLOSED`(영구 종료)는 없다. 감상문 생성이 도는 동안에만 `LOCKED`, 끝나면(성공이든 실패든) `ACTIVE` 로 복귀한다. → 사용자는 감상문을 만든 뒤에도 같은 세션에서 대화를 이어갈 수 있다 (이슈 #69 의 본체).
2. **Summary = 성공한 감상문의 write-once 기록.** 생성을 시작할 때 미리 만드는 행이 없다. 생성에 **성공해야** 비로소 COMPLETED 행이 새로 하나 생긴다. 한 번 만들어진 생성 기록은 사후에 상태가 바뀌지 않는다 (편집은 예외 — 아래 4절). 재생성은 행을 고치는 게 아니라 **새 행을 추가**한다. "현재 감상문" = 세션의 가장 최근 행.

## 확정된 결정

### 1. 생성 실패 시 행을 남기지 않는다 (선행 설계 갱신)

선행 설계는 실패를 `FAILED` 행으로 남기려 했으나, 이를 뒤집는다.

- "재시도가 필요하다 = 아직 감상문이 안 만들어졌다" 가 자연스러우려면, 실패는 기록이 아니어야 한다. dev 가 실패 행을 "재사용" 해야 했던 건 IN_PROGRESS 행을 미리 박아둔 탓이고, write-once 에는 되살릴 행이 없다.
- **실패 처리**: LLM 호출이 실패하면 원인을 **ERROR 로그로만** 남기고 세션을 `unlock()` 한다. Summary 테이블에는 아무것도 쓰지 않는다.
- **Summary 테이블은 항상 성공한 감상문만 담는다.** 따라서 `Summary.Status` 의 `FAILED`·`IN_PROGRESS` 는 모두 불필요해진다 (1절의 write-once 와 합쳐, Summary 에는 성공 상태만 남는다 — 구현 시 status 필드 제거 여부를 검토).
- 잃는 것: 사용자에게 "생성 실패" 를 영속적으로 알리는 신호. 폴링하는 프론트는 LOCKED(409 생성 중) → 행 없음(404) 전이로 "이번 시도는 결과가 없었다" 를 읽는다. MVP 에서는 재생성으로 충분하다고 판단해 감수한다.

### 2. 세션 상태와 엔티티

- `AiChatSession.Status` = `{ACTIVE, LOCKED}`. `close()`/`isClosed()` → `lock()`/`isLocked()`, 복귀용 `unlock()` 추가.
- `Summary`: `(ai_chat_session_id, summary_date)` 유니크 제약 제거, `retryCount` 제거. 생성 팩토리는 성공 기록 생성용 하나만 둔다(`createCompleted(...)`). `complete()`/`fail()`/`resetToInProgress()`/`createInProgress()` 삭제. `edit()` 는 4절에 따라 유지.
- `summaryDate` 컬럼: 유니크 제약이 사라지면서 "날짜당 1건" 의미가 없어진다. 시점은 `createdAt` 으로 충분하므로 제거하는 방향. (자동 생성·기록 목록·조회 어디서도 날짜 키가 필요 없음을 구현 계획에서 재확인)

### 3. 수동 감상문 생성 흐름 (`SummaryDraftService.execute`)

- **TX1 (동기)**: 세션 비관적 락 조회 → 자격 검증(ACTIVE + 누적 토큰 ≥ 500) → `session.lock()` → 대화 이력 로드 → 즉시 반환. Summary 행을 미리 만들지 않는다.
- **비동기**: LLM 호출.
- **TX2**: 성공 → COMPLETED Summary 행 생성 + `session.unlock()` 을 한 트랜잭션으로. 실패 → ERROR 로그 + `session.unlock()` (행 없음).
- 중복 생성 방지는 비관적 락 + "ACTIVE 일 때만 LOCKED 전이" 가 막는다.

### 4. 편집은 그대로 제자리 수정 (in-place)

- 감상문 편집은 생성과 별개의 사용자 행위다. write-once 원칙은 "생성 파이프라인이 미완성/실패 행을 미리 만들거나 되살리지 않는다" 는 것이지, 사용자가 자기 감상문 글을 다듬는 것까지 새 버전으로 쌓으라는 뜻은 아니다.
- `SummaryEditService` 는 dev 그대로 유지: 세션 소유권 검증 → 가장 최근 감상문 행을 찾아 `edit(title, body)` 로 제자리 수정.
- 편집 대상이 "완성된 감상문" 인지 확인하던 `isCompleted()` 가드는, 모든 행이 성공 기록이 되므로 자연히 항상 통과한다. status 필드를 제거하면 가드와 `SUMMARY_NOT_COMPLETED` 도 함께 정리한다 (구현 계획에서 확정).

### 5. 자동 생성 스케줄러 (`SummaryScheduler`) 재작업

dev 스케줄러는 옛 모델에 묶여 있어 두 군데를 바로잡는다.

**(a) 대상 선정 규칙을 채팅 활동 기준으로 다시 못박는다.**

dev 는 `세션 ACTIVE + 누적 토큰(평생) ≥ 500 + session.updatedAt 이 24h 이내` 로 1차 거른 뒤, `마지막 요약 이후 새 토큰 ≥ 500` 으로 2차로 걸렀다. 두 문제가 있다.

- `session.updatedAt` 은 `lock()`/`unlock()`/감상문 기록 같은 **채팅이 아닌 이벤트로도 갱신**된다. "최근에 대화했는가" 의 지표로 쓰기엔 오염돼 있다. (dev 의 세션 목록 쿼리는 이미 이 이유로 updatedAt 대신 `max(메시지 createdAt)` 을 쓴다 — 스케줄러만 예외)
- #69 로 세션이 `CLOSED` 로 빠지지 않고 영원히 `ACTIVE` 로 남게 되면서, 스케줄러 대상 풀이 "사용자가 대화한 적 있는 모든 세션" 으로 무한정 커진다.

→ **자동 요약 대상 = (마지막 COMPLETED 메시지의 createdAt 이 24시간 이내) AND (세션 누적 토큰 ≥ 500).** 24시간 판정을 `session.updatedAt` 이 아니라 **실제 마지막 채팅 시각(메시지 createdAt)** 으로 바꾼다. dev 가 추가로 걸던 "마지막 요약 이후 델타 토큰 ≥ 500" 게이트는 **제거한다** — "충분한 대화" 기준은 세션 전체 누적 토큰(≥ 500, 수동 생성과 동일한 기존 임계값)으로 본다. 대화를 멈춘 세션(최근 24h 채팅 없음)은 24시간 게이트로 자연히 빠지고, 최근 대화한 일정 규모 세션은 매일 다시 요약된다.

자동 생성이 LLM 에 넣는 대화 범위는 **세션 전체 대화**로 한다 — 수동 생성(`findValidMessagesBySessionIdOrderByCreatedAtAsc`, 전체 메시지)과 동일. 매 생성은 그 시점까지의 대화를 통째로 요약한 스냅샷이고, "현재 감상문 = 최신 행" 모델과 맞는다. (dev 의 "마지막 요약 이후 메시지만" 증분 방식은 버린다)

**(b) 10분 재시도 스케줄러(`retryFailedSummaries`)를 제거한다.** 실패 행 자체가 없어지므로(1절) 스캔할 대상이 없다. 같은 날 자동 복구는 사라지지만, 다음 날 6시 정기 실행(세션이 여전히 ACTIVE + 새 토큰 충족 시 다시 잡힘)과 사용자 수동 재생성으로 복구된다.

- 자동 생성도 수동 생성과 같은 구조로 맞춘다: `lock()` → 생성 → 성공 시 COMPLETED 행 + `unlock()`, 실패 시 로그 + `unlock()`. (LOCKED 전이가 동시 수동 요청과의 중복도 막는다)

### 6. 조회·기록·표시상태

- **`GET .../summary` (`SummarySearchService`)**: 세션 LOCKED → 409 `SUMMARY_IN_PROGRESS`. 그 외 최신 행 기준: COMPLETED → 200, 행 없음 → 404 `SUMMARY_NOT_FOUND`. (FAILED 분기 없음)
- **감상 기록 목록 (`SummaryHistorySearchService`)**: `CLOSED` 필터가 사라지므로 기준을 **"세션(=책)당 가장 최근 감상문 1건"** 으로 바꾼다. 모든 행을 노출하면 매일 자동 생성분이 목록을 도배하므로, 세션별 최신 행만 골라 최신순으로 보여준다.
- **세션 목록 표시 상태 (`AiChatSessionDisplayStatus`)** = `{ACTIVE, SUMMARIZING, SUMMARIZED}`. 산출:

| 상황 | 표시 상태 |
|---|---|
| 세션 LOCKED (생성 중) | `SUMMARIZING` |
| 세션 ACTIVE + 최신 감상문 있음 | `SUMMARIZED` |
| 세션 ACTIVE + 감상문 없음 | `ACTIVE` |

`FAILED` 표시 상태는 실패 행이 없어지므로 제거. 목록 JPQL 의 CASE 식은 dev 가 `Summary.IN_PROGRESS`/세션 `CLOSED` 를 참조하므로 새 모델에 맞게 다시 작성하고, 세션당 최신 Summary 한 건만 join 한다.

### 7. 에러 코드 (`AiChatErrorCode`)

| 코드 | 처리 |
|---|---|
| `SESSION_CLOSED` | → `SESSION_LOCKED` ("독후감 생성 중에는 메시지를 보낼 수 없습니다.") |
| `SESSION_ALREADY_CLOSED` | 삭제 — LOCKED 세션 생성 차단은 `SUMMARY_IN_PROGRESS` 재사용 |
| `SUMMARY_GENERATION_FAILED` | 삭제 — 실패 행이 없어 409 실패 경로가 사라짐 |
| `SUMMARY_NOT_COMPLETED` | status 필드 제거 시 함께 삭제 (편집 가드가 자명해짐) |
| `SUMMARY_IN_PROGRESS`, `SUMMARY_NOT_FOUND` | 유지 |

### 8. 데이터 마이그레이션 (dev RDS)

dev RDS 는 초기화 후 재생성 가능하므로(2026-06-13 사용자 확인) 마이그레이션 SQL 은 작성하지 않는다. 배포 시 RDS 초기화를 함께 수행한다. 테스트 DB 는 H2 인메모리(create-drop)라 엔티티 어노테이션 변경만으로 스키마가 반영된다.

### 9. 신규 기능: 책별 대화 세션 목록

한 권(책)에 대해 만든 모든 채팅 세션을, 각 세션의 최신 감상문과 함께 한눈에 보는 조회 화면.

- **엔드포인트**: `GET /api/v1/ai-chat/books/{userBookId}/sessions`. 인증은 기존 user_session 쿠키 → User, `userBookId` 소유권 검증(본인 책장의 책인지).
- **응답**:

```json
{ "data": {
    "book": {
      "title": "...",
      "publishedYear": 2020,
      "publisher": "...",
      "coverImageUrl": "..."
    },
    "sessions": [
      { "sessionId": 1, "latestSummaryContent": "...본문...", "lastChattedDate": "2026-06-13" }
    ]
} }
```

- `book` — `Book` 의 `title`/`publishedYear`/`publisher`/`coverUrl`(→ `coverImageUrl`).
- `sessions[]` — 그 책의 모든 채팅 세션. 페이지네이션 없음(책당 세션 수는 제한적).
  - `latestSummaryContent`: 세션의 **가장 최근 감상문 본문(body)** 만. 제목은 주지 않는다. 감상문이 아직 없는 세션은 `null`.
  - `lastChattedDate`: 마지막 COMPLETED 메시지의 날짜를 **ISO(`2026-06-13`)** 로. 표시 포맷(yymmdd 등)은 프론트가 처리. 메시지가 없는 세션은 세션 생성일 fallback.
  - 정렬: `lastChattedDate` 최신순.
- **데이터 접근**: 프로젝트 기존 컨벤션(repository 는 자기 엔티티 반환, 타 엔티티 조합은 service 가 각 repository 호출로 합성)에 따라 service 에서 합성한다. 책 정보(UserBook→Book), 세션 목록(userBookId 기준), 세션별 마지막 채팅일(AiChatMessage createdAt 집계), 세션별 최신 감상문 본문을 각각 조회해 묶는다. 구체 쿼리 형태는 구현 계획에서 확정.

### 10. 알려진 한계 (이번 범위 밖)

- 생성 도중 서버가 죽으면 세션이 `LOCKED` 로 남는다 (기존에도 동일). 복구 정책은 별도 이슈.
- `@Async` self-invocation(`execute` 가 같은 클래스의 `generateAsync` 직접 호출)이 프록시를 안 타 동기 실행되는 문제 (기존 결함). 별도 이슈.

## 재작업·병합 전략

- 현재 브랜치 `feature/69-resume-summarized-session` 의 커밋들은 옛 dev 기준이라 대부분 다시 손봐야 한다. `origin/dev` 를 브랜치에 병합해 dev 의 스케줄러·편집·기록을 들여온 뒤, 충돌·중복 파일을 위 통합 모델로 재작성한다.
- 양쪽이 함께 건드린 파일(충돌 예상): `Summary`, `SummaryRepository`, `SummaryDraftService`, `SummarySearchService`, `SummaryDraftPolicy`, `AiChatErrorCode`, `AiChatSessionRepository`, `AiChatController` 와 각 테스트·픽스처.

## 테스트 계획

서비스 단위 테스트 + 조회 DAO 통합 테스트. 핵심 시나리오:

- 생성 성공 시 세션이 ACTIVE 로 복귀하고 COMPLETED 행이 추가된다
- 생성 실패 시 세션이 ACTIVE 로 복귀하고 **행은 추가되지 않는다** (로그만)
- 복귀한 세션에 메시지를 보낼 수 있다 / LOCKED 세션은 400 으로 차단된다
- LOCKED 세션에 생성을 요청하면 409 로 차단된다
- 재생성하면 새 행이 추가되고 기존 행이 보존된다 (최신 행이 "현재 감상문")
- `GET /summary`: LOCKED → 409 / 최신 COMPLETED → 200 / 없음 → 404
- 목록 표시 상태 3종(SUMMARIZING / SUMMARIZED / ACTIVE) 산출 — DAO 통합 테스트
- 편집: 최신 감상문 행이 제자리 수정된다
- 기록 목록: 세션당 최신 1건만, 최신순으로 노출된다
- 스케줄러 대상 선정: 마지막 채팅 24h 이내 + 세션 누적 토큰 ≥ 500 인 세션만 잡히고, 24h 내 채팅이 없는 세션·누적 토큰 부족 세션은 제외된다
- 책별 대화 세션 목록: 책 정보 + 세션별 최신 감상문 본문(없으면 null) + 마지막 채팅일이 최신순으로 반환된다; 타인의 userBook 조회는 차단된다 — DAO 통합 테스트 포함

## 프론트 조율 사항

- 세션 목록 표시 상태에서 `CLOSED`/`FAILED` 제거, `SUMMARIZED` 추가
- eligibility 응답의 reason 문자열 변경 (`SESSION_ALREADY_CLOSED` → `SUMMARY_IN_PROGRESS`)
- `GET /summary` 에서 "생성 실패" 409 가 사라지고, 실패 시 404 로 보임
- 감상문 완료 후에도 대화 입력이 가능해지는 화면 흐름
