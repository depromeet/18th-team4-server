# AI 채팅 (aiChat) 시나리오

## 1. 세션 생성

사용자가 자기 책장의 책(userBook) 한 권에 대해 새 채팅 세션을 만든다 (`AiChatSessionCreateService`).

```mermaid
flowchart TD
    A["POST /api/v1/ai-chat/sessions"] --> B["AiChatController.createSession"]
    B --> C["AiChatSessionCreateService.execute (@Transactional)"]
    C --> D{"userBookRepository.findByIdAndUserId — 본인 소유 도서인가"}
    D -->|아니오| E["404 USER_BOOK_NOT_FOUND"]
    D -->|예| F["AiChatSession.create(userBookId) 저장 — status ACTIVE"]
    F --> G["201 Created + AiChatSessionCreateResponse"]
```

## 2. 세션 목록 조회 — 표시 상태 합성

한 책(userBook)의 세션들을 페이지 조회하며, DB 의 두 상태(세션 ACTIVE/LOCKED + summary_job 존재 여부)를 JPQL CASE 로 화면용 ACTIVE/SUMMARIZING/SUMMARIZED 로 합성한다 (`AiChatSessionSearchService`).

```mermaid
flowchart TD
    A["GET /api/v1/ai-chat/sessions"] --> B["AiChatSessionSearchService.findByUserBookId"]
    B --> C{"userBook 소유 확인"}
    C -->|아니오| D["404 USER_BOOK_NOT_FOUND"]
    C -->|예| E["AiChatSessionRepository.findSessionsByUserBookIdAndOwner — Slice 페이지 조회"]
    E --> F{"JPQL CASE 로 세션별 표시 상태 합성"}
    F -->|"세션 status = LOCKED"| G["SUMMARIZED — 감상문 완성·영구 종료"]
    F -->|"summary_job 에 PENDING 또는 유효 점유(lockedUntil > now) PROCESSING 존재"| H["SUMMARIZING — 감상문 생성 중"]
    F -->|그 외| I["ACTIVE — 대화 가능"]
    G --> J["lastChattedAt = 마지막 COMPLETED 메시지 시각, 없으면 세션 createdAt — 내림차순 정렬"]
    H --> J
    I --> J
    J --> K["200 OK + 세션 목록(hasNext 포함), 없으면 빈 배열"]
```

## 3. 책별 세션 목록 조회

한 책의 모든 세션을 책 정보·세션별 최신 감상문 본문·마지막 대화일과 함께 조회한다 — repository 는 각자 자기 엔티티만 반환하고 합성은 service 책임 (`BookChatSessionSearchService`).

```mermaid
flowchart TD
    A["GET /api/v1/ai-chat/books/{userBookId}/sessions"] --> B["BookChatSessionSearchService.findByUserBook"]
    B --> C{"userBook 소유 확인 + Book 존재 확인"}
    C -->|아니오| D["404 USER_BOOK_NOT_FOUND"]
    C -->|예| E["AiChatSessionRepository.findByUserBookId — 세션 전체 조회"]
    E --> F["SummaryRepository.findLatestByAiChatSessionIdIn — 세션별 최신 감상문 본문"]
    E --> G["AiChatMessageRepository.findLastChattedAtBySessionIds — 세션별 마지막 COMPLETED 메시지 시각"]
    F --> H["service 가 합성 — 감상문 없으면 null, 메시지 없으면 세션 생성일로 대체"]
    G --> H
    H --> I["마지막 대화일 내림차순 정렬 → 200 OK"]
```

## 4. 메시지 전송 (SSE 스트리밍)

사용자 메시지를 검증·moderation 후 저장하고 AI 응답을 SSE 로 흘려보낸다 — 정상/에러/중단 종료를 모두 영속화 정책으로 흡수한다 (`AiChatMessageSendService`).

```mermaid
flowchart TD
    A["POST /api/v1/ai-chat/sessions/{sessionId}/messages"] --> B{"AiChatRateLimitInterceptor — userId 키, 분당 20 · 일 200 (dev, infrastructure)"}
    B -->|한도 초과| C["429 + 에러 JSON — SSE 시작 전"]
    B -->|통과| D["AiChatMessageSendService.execute"]
    D --> E{"본문 검증 — 공백 정규화 후 빈 값 또는 1,000자 초과"}
    E -->|위반| F["400 MESSAGE_CONTENT_BLANK / MESSAGE_CONTENT_TOO_LONG"]
    E -->|통과| G{"Redis ZSET 폭주 가드 — 10초 내 전송 시도 5건 (Lua 로 검사·기록 원자 수행)"}
    G -->|초과| H["429 USER_RATE_LIMIT_EXCEEDED + Retry-After 헤더"]
    G -->|통과| I["AiChatMessagePersistService.loadHistory — USER 메시지는 아직 저장하지 않음"]
    I --> J{"세션 소유 확인"}
    J -->|아니오| K["404 SESSION_NOT_FOUND"]
    J -->|예| L{"세션 LOCKED 인가"}
    L -->|예| M["400 SESSION_ALREADY_SUMMARIZED — 감상문 확정, 영구 대화 불가"]
    L -->|아니오| N{"활성 summary_job 존재 — existsBlockingSummaryJob"}
    N -->|예| O["400 SESSION_LOCKED — 감상문 생성 중, 일시 전송 불가"]
    N -->|아니오| P["컨텍스트 조립 — 누적 요약 + 요약 반영 지점 이후 COMPLETED 최근 원문 대화(토큰 예산 기반, 최근 원문 최대 8,000) + 책 정보"]
    P --> Q{"InputModerationClient.check — SSE 시작 전 동기 호출"}
    Q -->|BLOCKED| R["USER 메시지 REJECTED 저장 → 400 GUARDRAIL_BLOCKED_INPUT"]
    Q -->|UNAVAILABLE| S["저장 없이 503 GUARDRAIL_MODERATION_UNAVAILABLE — 판정 불가 시 차단"]
    Q -->|PASSED| T["USER 메시지 COMPLETED 저장 + 세션 턴 카운트 증가"]
    T --> U["AiChatClient.generateStream — OpenAiChatModel 직접 호출, 서버 소유 구독 (infrastructure)"]
    U --> V["token 이벤트 스트리밍 — delta 를 즉시 전송하며 본문 누적"]
    V --> W{"스트림 종료 방식"}
    W -->|"정상 종료 (usage 청크 도착)"| X["saveAssistantSuccess — ASSISTANT COMPLETED 저장 + 출력 토큰 누적, 첫 응답이면 제목 생성 이벤트, 커밋 후 컨텍스트 요약 트리거 이벤트(최근 원문 대화>4,000이면 요약 job 적재)"]
    X --> Y["done 이벤트 (tokenCount, createdAt)"]
    W -->|"스트림 에러 (OpenAI 429/5xx, 네트워크)"| Z["error 이벤트 즉시 전송 + 부분 응답 FAILED 비동기 저장"]
    W -->|"클라이언트 중단 (cancel)"| AB{"수신 상태"}
    AB -->|"완료 메타 도착됨"| AC["의미상 정상 종료 — COMPLETED 저장"]
    AB -->|"부분 응답만 존재"| AD["FAILED 저장 — 컨텍스트 윈도우에서 제외돼 다음 턴 오염 없음"]
    AB -->|"응답 0byte"| AE["저장 생략"]
```

## 5. 메시지 이력 조회

세션의 메시지를 최신순으로 페이지 조회한다 — COMPLETED 만 노출하고 FAILED(부분 응답)·REJECTED(차단 입력)는 제외한다 (`AiChatMessageSearchService`).

```mermaid
flowchart TD
    A["GET /api/v1/ai-chat/sessions/{sessionId}/messages"] --> B["AiChatMessageSearchService.findBySessionId"]
    B --> C{"세션 소유 확인 — findByIdAndOwner"}
    C -->|아니오| D["404 SESSION_NOT_FOUND"]
    C -->|예| E["AiChatMessageRepository.findVisibleHistory — status COMPLETED 만, createdAt·id 내림차순 Slice"]
    E --> F["200 OK + 메시지 목록(hasNext 포함) — FAILED·REJECTED 는 응답에 없음"]
```

## 6. 감상문 조회

세션의 감상문을 조회한다 — 생성 중이면 409 로 폴링을 유도하고, 실패로 행이 없으면 404 (`SummarySearchService`, summary 도메인).

```mermaid
flowchart TD
    A["GET /api/v1/ai-chat/sessions/{sessionId}/summary"] --> B["SummarySearchService.findBySessionId (summary 도메인)"]
    B --> C{"세션 소유 확인"}
    C -->|아니오| D["404 SESSION_NOT_FOUND"]
    C -->|예| E{"생성 중인가 — existsBlockingSummaryJob (PENDING 또는 유효 점유 PROCESSING)"}
    E -->|예| F["409 SUMMARY_IN_PROGRESS — 잠시 후 재시도"]
    E -->|아니오| G{"SummaryRepository.findByAiChatSessionId — 감상문 존재 (세션당 1행)"}
    G -->|없음| H["404 SUMMARY_NOT_YET_CREATED — 생성 실패 시 행을 남기지 않으므로 세션은 ACTIVE 로 남아 재요청 가능"]
    G -->|있음| I["200 OK + SummaryResponse"]
```

## 7. 감상문 수정

완성된 감상문의 제목·본문을 사용자가 직접 수정한다 — LLM 무관 (`SummaryEditService`).

```mermaid
flowchart TD
    A["PUT /api/v1/ai-chat/sessions/{sessionId}/summary"] --> B["SummaryEditService.execute (@Transactional)"]
    B --> C{"세션 소유 확인"}
    C -->|아니오| D["404 SESSION_NOT_FOUND"]
    C -->|예| E{"SummaryRepository.findByAiChatSessionId — 감상문 존재"}
    E -->|없음| F["404 SUMMARY_NOT_FOUND"]
    E -->|있음| G["summary.edit(title, body) — dirty checking 으로 갱신"]
    G --> H["200 OK + 수정된 SummaryResponse"]
```

## 8. 감상문 초안 자격·진행률 조회

부수 효과 없이 초안 생성 가능 여부·사유·진행률(누적 토큰 500 기준 %)을 반환한다 (`SummaryDraftSearchService` + `SummaryDraftPolicy`).

```mermaid
flowchart TD
    A["GET /api/v1/ai-chat/sessions/{sessionId}/summary-draft/eligibility"] --> B["SummaryDraftSearchService.findEligibility"]
    B --> C{"세션 존재 + userBook 소유 확인"}
    C -->|아니오| D["404 SESSION_NOT_FOUND"]
    C -->|예| E["progressPercent = min(accumulatedTokens × 100 / 500, 100)"]
    E --> F{"활성 summary_job 존재 — existsByActiveSessionId"}
    F -->|예| G["eligible=false, 사유 SUMMARY_IN_PROGRESS"]
    F -->|아니오| H{"SummaryDraftPolicy.evaluate — 세션 상태"}
    H -->|LOCKED| I["eligible=false, 사유 ALREADY_SUMMARIZED"]
    H -->|"ACTIVE 이고 누적 토큰 < 500"| J["eligible=false, 사유 CHAT_VOLUME_NOT_ENOUGH"]
    H -->|"ACTIVE 이고 누적 토큰 >= 500"| K["eligible=true"]
    G --> L["200 OK — eligible=false 도 200"]
    I --> L
    J --> L
    K --> L
```

## 9. 감상문 초안 생성 요청 — 작업 적재만

생성 요청을 받아 SummaryJob 을 큐에 적재만 하고 202 를 반환한다 — 실제 생성은 백그라운드 워커 몫 (`SummaryDraftService` → `EnqueueSummaryJobService`).

```mermaid
flowchart TD
    A["POST /api/v1/ai-chat/sessions/{sessionId}/summary-draft"] --> B{"AiChatRateLimitInterceptor — userId 키 (infrastructure)"}
    B -->|한도 초과| C["429 + 에러 JSON"]
    B -->|통과| D["SummaryDraftService.execute (@Transactional)"]
    D --> E{"세션 존재 + userBook 소유 확인"}
    E -->|아니오| F["404 SESSION_NOT_FOUND"]
    E -->|예| G{"활성 summary_job 존재 — existsByActiveSessionId"}
    G -->|예| H["409 SUMMARY_IN_PROGRESS — 이미 생성 진행 중"]
    G -->|아니오| I{"SummaryDraftPolicy.assertEligible"}
    I -->|"세션 LOCKED"| J["409 SESSION_ALREADY_SUMMARIZED"]
    I -->|"누적 토큰 < 500"| K["422 CHAT_VOLUME_NOT_ENOUGH"]
    I -->|자격 충족| L["EnqueueSummaryJobService.execute (summary 도메인) — 멱등 적재, INSERT 는 REQUIRES_NEW 격리 + 락 경합 시 backoff 재시도"]
    L --> M{"적재 결과 — active_session_id unique 로 동시 요청 중 한쪽만 성공"}
    M -->|"enqueued=false (경합 패배)"| N["409 SUMMARY_IN_PROGRESS"]
    M -->|enqueued=true| O["202 Accepted — 결과는 GET summary 폴링으로 확인"]
```

## 10. 세션 제목 자동 생성 (비동기)

첫 ASSISTANT 응답 저장이 커밋된 뒤 이벤트로 트리거되어, 전용 격벽 스케줄러에서 LLM 으로 제목을 만들고 짧은 트랜잭션으로 갱신한다 (`AiChatTitleGenerationListener` → `AiChatSessionTitleService`).

```mermaid
flowchart TD
    A["saveAssistantSuccess 트랜잭션 — 세션의 첫 COMPLETED USER 메시지 턴이면 FirstAssistantResponseCompletedEvent 발행"] --> B{"트랜잭션 커밋 성공"}
    B -->|"롤백"| C["이벤트 미발화 — AFTER_COMMIT 이므로 저장 확정 시에만 진행"]
    B -->|커밋| D["AiChatTitleGenerationListener (@TransactionalEventListener AFTER_COMMIT)"]
    D --> E{"titleGenerationScheduler 로 offload — 전용 격벽, threadCap 32 / thread 당 queueCap 64"}
    E -->|"큐 포화 (RejectedExecutionException)"| F["에러 로그만 남기고 종료 — 채팅 본편에 영향 없음"]
    E -->|수용| G["AiChatSessionTitleService.execute"]
    G --> H{"existsById — 세션 존재 (짧은 readOnly 트랜잭션)"}
    H -->|아니오| I["404 성격의 NotFoundException — 리스너가 로그만 남김"]
    H -->|예| J["AiChatTitleClient.generate — 트랜잭션 밖 LLM 호출, 첫 USER 질문만 입력"]
    J --> K{"생성 결과"}
    K -->|"비어 있음"| L["갱신 생략 (경고 로그)"]
    K -->|"LLM 실패"| M["에러 로그만 — 사용자 흐름과 무관"]
    K -->|정상| N["짧은 트랜잭션으로 session.updateTitle — dirty-check flush"]
```

## 11. 감상문 자동 생성 적재 (매일 06:00)

스케줄러가 자격 있는 세션의 생성 작업을 집합 단위 단일 INSERT 로 적재한다 — 직접 생성하지 않는다 (`SummaryScheduler`, infrastructure).

```mermaid
flowchart TD
    A["SummaryScheduler.enqueueDailySummaryJobs — @Scheduled cron 매일 06:00 (@Transactional)"] --> B["SummaryJobRepository.enqueuePendingForEligibleSessions — 네이티브 단일 INSERT (per-row 루프 아님)"]
    B --> C{"대상 세션 조건"}
    C --> D["status = ACTIVE (LOCKED 세션 자동 제외)"]
    C --> E["accumulatedTokens >= 500 (SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS)"]
    C --> F["24시간 내 COMPLETED 메시지 존재"]
    D --> G{"중복 방어"}
    E --> G
    F --> G
    G -->|"NOT EXISTS 활성 작업 + active_session_id unique + INSERT IGNORE"| H["충돌 행만 건너뛰고 PENDING 작업 적재"]
    H --> I["적재 건수 로그 — 실제 생성은 작업 큐 워커가 OpenAI 한도에 맞춰 처리 (summary 도메인)"]
```

## 12. 세션 영구 잠금 — 감상문 확정과 같은 트랜잭션

워커가 생성에 성공하면 감상문 저장 + `session.lock()` + 작업 성공 처리를 한 트랜잭션으로 묶는다 — LOCKED 는 영구 상태로 되돌리는 메서드가 없다 (`SummaryJobLifecycleService.recordSuccess`, summary 도메인).

```mermaid
flowchart TD
    A["SummaryGenerationWorker (summary 도메인) — 작업 선점(claimOne, SKIP LOCKED) 후 트랜잭션 밖에서 AiSummaryClient.generate 호출 성공"] --> B["SummaryJobLifecycleService.recordSuccess (@Transactional)"]
    B --> C{"작업 비관적 락 조회 + lock_owner 일치 확인 — lease 만료로 재선점된 작업 펜싱"}
    C -->|"소유권 상실"| D["아무것도 반영하지 않고 종료 (경고 로그)"]
    C -->|일치| E{"세션 비관적 락 조회 — status 가 ACTIVE 인가"}
    E -->|"아니오 (이미 종료/없음)"| F["작업만 markSucceeded — 세션은 건드리지 않음"]
    E -->|예| G["Summary.createCompleted 저장 (세션당 1행)"]
    G --> H["session.lock() — ACTIVE → LOCKED 단방향, unlock 없음"]
    H --> I["job.markSucceeded — 세 변경이 한 트랜잭션으로 커밋"]
    I --> J["이후 세션은 목록에서 SUMMARIZED 로 표시되고 메시지 전송은 400 SESSION_ALREADY_SUMMARIZED"]
```
