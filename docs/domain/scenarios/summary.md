# 감상 기록 (summary) 시나리오

## 1. 감상문 생성 작업 적재

수동 요청(aiChat 도메인 `SummaryDraftService` 경유)과 매일 06:00 자동 스케줄러 두 경로로 `summary_job` 을 적재하며, `active_session_id` unique 제약으로 "세션당 활성 작업 1개" 멱등을 보장한다.

```mermaid
flowchart TD
    subgraph manual["수동 적재 경로"]
        M1["SummaryDraftService.execute (aiChat 도메인)"] --> M2{"세션 존재 + 본인 소유 확인"}
        M2 -->|"실패"| M3["404 SESSION_NOT_FOUND"]
        M2 -->|"통과"| M4{"existsByActiveSessionId — 이미 활성 작업 존재?"}
        M4 -->|"예"| M5["409 SUMMARY_IN_PROGRESS"]
        M4 -->|"아니오"| M6{"SummaryDraftPolicy 자격 검증 (aiChat 도메인)"}
        M6 -->|"세션 LOCKED — 이미 감상문 완성"| M7["409 SESSION_ALREADY_SUMMARIZED"]
        M6 -->|"누적 토큰이 500 미만"| M8["422 CHAT_VOLUME_NOT_ENOUGH"]
        M6 -->|"통과"| E1
    end

    subgraph enqueue["EnqueueSummaryJobService — 멱등 적재"]
        E1["execute(sessionId)"] --> E2{"existsByActiveSessionId 사전 확인"}
        E2 -->|"이미 있음"| E3["enqueued=false 반환"]
        E2 -->|"없음"| E4["SummaryJobInserter.insertPending — REQUIRES_NEW 독립 트랜잭션으로 PENDING 행 insert"]
        E4 -->|"insert 성공"| E5["enqueued=true — SummaryJob PENDING 생성"]
        E4 -->|"DataIntegrityViolationException — unique 위반"| E6{"활성 작업 재확인"}
        E6 -->|"있음 — 동시 적재 경합"| E3
        E6 -->|"없음 — FK/NOT NULL 등 다른 무결성 위반"| E7["예외 재던짐"]
        E4 -->|"락 대기 실패 — CannotAcquireLockException / LockTimeoutException"| E8["@Retryable 재시도 — 최대 3회, backoff 200ms x2 최대 1초, 매번 사전 확인부터 다시"]
        E8 --> E2
    end

    E3 --> M9["수동 경로는 409 SUMMARY_IN_PROGRESS 로 응답"]

    subgraph auto["자동 적재 경로"]
        A1["SummaryScheduler — 매일 06:00 cron (aiChat 도메인)"] --> A2["enqueuePendingForEligibleSessions — 집합 단위 단일 INSERT IGNORE 네이티브 SQL"]
        A2 --> A3["대상: 세션 ACTIVE + 누적 토큰 500 이상 + 24시간 내 COMPLETED 메시지 존재"]
        A3 --> A4["NOT EXISTS 활성 작업 조건 + active_session_id unique + INSERT IGNORE 로 중복 적재 흡수"]
        A4 --> A5["적재 건수 INFO 로그"]
    end
```

## 2. 감상문 생성 파이프라인

디스패처가 2초 간격으로 워커를 풀(12)에 제출하고, 워커는 SKIP LOCKED 로 작업을 선점한 뒤 트랜잭션 밖에서 OpenAI 를 호출해 성공 시 Summary 저장 + 세션 잠금, 실패 시 분류에 따라 재시도, 재시도 횟수를 올리지 않는 반납, FAILED 처리한다.

```mermaid
flowchart TD
    D1["SummaryJobDispatcher — 2초 간격 @Scheduled"] --> D2["빈 슬롯 수 = poolSize 12 - 실행 중 워커 수"]
    D2 --> D3{"summaryExecutor 에 워커 제출"}
    D3 -->|"RejectedExecutionException — 풀 포화"| D4["카운터 되돌리고 이번 틱 중단, 다음 dispatch 에서 재시도"]
    D3 -->|"제출 성공"| W1["SummaryGenerationWorker.processUntilEmpty — 작업이 없을 때까지 반복"]

    W1 --> W2{"breaker.isBlocked — 전역 차단 중?"}
    W2 -->|"예"| W3["이번 사이클 종료 — 다음 dispatch 때 재확인"]
    W2 -->|"아니오"| C1["claimOne — PENDING 이고 nextAttemptAt 이 지난 작업을 SKIP LOCKED 로 선점, READ COMMITTED"]
    C1 -->|"작업 없음"| W3
    C1 -->|"선점"| C2["PROCESSING 전이 + lock_owner UUID + lease 300초"]

    C2 --> P1{"prepareGeneration — 비관적 락 + lock_owner 펜싱 확인"}
    P1 -->|"소유권 상실"| W1
    P1 -->|"세션이 ACTIVE 아님 — 이미 종료/없음"| P2["작업만 SUCCEEDED 처리, 감상문 미생성"]
    P1 -->|"세션 ACTIVE"| T1["세션 대화 수집 + SummaryTokenEstimator — 글자수/2.5 + 예약 출력 토큰 1024"]

    T1 --> T2{"추정 토큰이 maxRequestTokens 120000 초과?"}
    T2 -->|"예 — 영원히 예산 확보 불가"| F1["recordFailure retryable=false → FAILED SESSION_TOO_LARGE"]
    T2 -->|"아니오"| R1{"SummaryCallRateLimiterImpl.tryAcquire — RPM 9000 / TPM 180000 token bucket(bucket4j), 최대 10초 대기"}
    R1 -->|"예산 미확보 — backpressure"| R2["releaseWithoutPenalty — 시도 횟수 미증가로 PENDING 반납"]
    R1 -->|"확보"| L1{"AiSummaryClientImpl.generate — OpenAI 호출, JSON Schema 구조화 응답, 감사 로그 기록"}

    L1 -->|"성공"| S1{"recordSuccess — 펜싱 + 세션 상태 재확인"}
    S1 -->|"소유권 상실"| S5["WARN 로그만 남기고 반영 안 함"]
    S1 -->|"세션이 ACTIVE 아님"| P2
    S1 -->|"정상"| S2["Summary.createCompleted 저장 — 세션과 1:1, uk_summary_session unique"]
    S2 --> S3["세션 LOCKED 로 영구 잠금 (aiChat 도메인)"]
    S3 --> S4["작업 SUCCEEDED"]

    L1 -->|"429 quota — AI_QUOTA_EXHAUSTED"| Q1["InMemorySummaryCallBreaker 300초 전역 차단 + recordFailure retryable=true"]
    L1 -->|"429 burst — 우리 과속"| B1["breaker 를 Retry-After 만큼(없으면 5초) 차단 + releaseWithoutPenalty — attemptCount 를 올리지 않고 PENDING 반납"]
    L1 -->|"4xx — NonTransientAiException"| F2["recordFailure retryable=false → FAILED AI_PROVIDER_ERROR"]
    L1 -->|"5xx — TransientAiException"| RT1["recordFailure retryable=true AI_PROVIDER_TRANSIENT"]
    L1 -->|"알 수 없는 오류 — 보수적으로 재시도"| RT1

    Q1 --> RT2
    RT1 --> RT2{"attemptCount+1 이 maxAttempts 5 미만?"}
    RT2 -->|"예"| RT3["scheduleRetry — PENDING 복귀, attemptCount+1, 지수 백오프 60초 x 2^attemptCount, Retry-After 있으면 그 시각"]
    RT2 -->|"아니오"| RT4["markFailed → FAILED + ERROR 로그, active_session_id 해제로 새 작업 적재 허용"]

    S4 --> W1
    P2 --> W1
    R2 --> W1
    F1 --> W1
```

## 3. 고아 작업 회수

서버 재시작·워커 장애로 lease(300초)가 만료된 PROCESSING 작업을 SummaryJobReaper 가 60초마다 PENDING 으로 되돌리고, 늦게 돌아온 옛 워커의 반영 시도는 lock_owner 펜싱으로 차단한다.

```mermaid
flowchart TD
    R1["SummaryJobReaper — 60초 간격 @Scheduled"] --> R2["reclaimOrphans — 한 번에 최대 100건"]
    R2 --> R3{"findOrphaned — PROCESSING 이고 lockedUntil 이 지난 작업, SKIP LOCKED, READ COMMITTED"}
    R3 -->|"없음"| R4["종료 — 로그 없음"]
    R3 -->|"있음"| R5["releaseAfterOrphan — PENDING 복귀, lock_owner/lockedUntil 해제, nextAttemptAt=now, 시도 횟수 미증가"]
    R5 --> R6["회수 건수 WARN 로그"]
    R5 --> R7["다음 dispatch 사이클에서 즉시 재선점 가능"]
    R7 --> R8{"lease 만료 후 옛 워커가 뒤늦게 recordSuccess / recordFailure / releaseWithoutPenalty 호출?"}
    R8 -->|"lock_owner 불일치"| R9["펜싱으로 반영 차단 — WARN 로그만 남김"]
```

## 4. 감상 기록 조회 3종 (목록 / 월별 캘린더 / 상세)

SummaryController 의 조회 API 세 개 — 본인 감상 기록 목록(Slice 20개), 홈 캘린더용 월별 독서 기록, 감상문 단건 상세.

```mermaid
flowchart TD
    subgraph list["감상 기록 목록 — GET /api/v1/summaries"]
        L1["SummaryHistorySearchService.findMyHistory"] --> L2["findLatestHistoryByUserId — Summary + AiChatSession + UserBook + Book join projection, userId 필터가 소유권 검증 겸함"]
        L2 --> L3["세션당 최신 감상문 1건, createdAt 내림차순, 페이지 크기 20 고정 Slice"]
        L3 --> L4["200 — 항목 리스트 + hasNext, 기록 없으면 빈 배열"]
    end

    subgraph calendar["월별 독서 기록 — GET /api/v1/summaries/calendar"]
        C1["SummarySearchService.findMonthly — @Transactional(readOnly=true) 로 여러 Repository 를 한 스냅샷으로 묶음"] --> C2{"내 UserBook 존재?"}
        C2 -->|"없음"| C3["200 — 빈 배열"]
        C2 -->|"있음"| C4["UserBook 들의 세션 조회 + 세션별 마지막 COMPLETED 메시지 시각 조회"]
        C4 --> C5{"마지막 채팅 시각이 해당 월 안?"}
        C5 -->|"채팅이 한 번도 없는 세션"| C6["결과에서 제외"]
        C5 -->|"해당 월"| C7["세션별 최신 감상문 id 조회 — 없으면 summaryId=null 로 포함"]
        C7 --> C8["책 제목 합성 후 lastChattedAt 내림차순, 같으면 chatSessionId 내림차순 정렬"]
        C8 --> C9["200 — 평탄한 리스트, 날짜별 그룹핑은 클라이언트 담당"]
    end

    subgraph detail["감상 기록 상세 — GET /api/v1/summaries/{summaryId}"]
        D1["SummarySearchService.findById"] --> D2{"Summary 존재?"}
        D2 -->|"없음"| D3["404 SUMMARY_NOT_FOUND"]
        D2 -->|"있음"| D4{"UserBook 이 본인 소유?"}
        D4 -->|"아니오 — 소유권도 404 로 감춤"| D3
        D4 -->|"예"| D5["200 — 제목·본문 반환"]
    end
```

## 5. SummaryJob 상태 전이

`SummaryJob.Status` enum(PENDING / PROCESSING / SUCCEEDED / FAILED)의 전이 — 미완료(PENDING/PROCESSING) 동안만 active_session_id 가 세션 id 를 유지해 unique 제약으로 멱등을 보장한다.

```mermaid
stateDiagram-v2
    [*] --> PENDING : createPending — 적재, active_session_id = 세션 id, attemptCount 0
    PENDING --> PROCESSING : claim — 워커 선점, lock_owner UUID + lease 300초
    PROCESSING --> SUCCEEDED : markSucceeded — 감상문 저장 성공, 또는 세션이 ACTIVE 아니어서 종료. active_session_id 해제
    PROCESSING --> FAILED : markFailed — 회복 불가 4xx, 세션 과대, 또는 시도 상한 5회 도달. active_session_id 해제
    PROCESSING --> PENDING : scheduleRetry — 일시 실패, attemptCount+1, 지수 백오프 후 재시도
    PROCESSING --> PENDING : releaseAfterOrphan — lease 만료 회수 또는 재시도 횟수를 올리지 않는 반납 (attemptCount 미증가)
    SUCCEEDED --> [*]
    FAILED --> [*]
```
