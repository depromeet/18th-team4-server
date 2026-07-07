# 0007 — 컨텍스트 요약 작업 적재를 비동기·best-effort 로 (감상문 동기 적재와의 분기)

- **상태**: 채택
- **날짜**: 2026-07-07
- **관련**: [0005](0005-context-summary-concurrency-transaction.md)(워커 처리 단계의 동시성·트랜잭션), [0006](0006-openai-rate-limit-defense-flow.md)(OpenAI 호출 방어), 이슈 #92(요약 큐 적재 락 타임아웃 사고), #103(AI 채팅 동시성 병목)
- **관련 코드**:
  - `domain/aiChat/service/EnqueueContextSummaryJobService` — 적재 판정·멱등·비-트랜잭션 오케스트레이터
  - `domain/aiChat/service/ContextSummaryJobInserter` — REQUIRES_NEW 로 격리한 INSERT
  - `domain/aiChat/listener/ContextSummarizeTriggerListener` — AFTER_COMMIT + boundedElastic 비동기 offload
  - `domain/summary/service/EnqueueSummaryJobService` + `domain/aiChat/service/SummaryDraftService` — 비교 대상(감상문 동기 적재)
  - `model/aiChat/repository/AiChatMessageRepository#sumRecentMessageTokens` + `V4` 인덱스 — 매 턴 판정 핫패스

## 한 줄 요약

컨텍스트 요약 작업의 **적재(생산자 단계)**는 감상문 생성과 근본적으로 성격이 다르다. 감상문 적재는 **사용자가 기다리는 동기 one-shot**이지만, 컨텍스트 요약 적재는 **ASSISTANT 응답 커밋 후 매 턴 자동으로 도는 best-effort 뒷정리**다. 그래서 적재를 `AFTER_COMMIT` + `boundedElastic` 로 **비동기 offload** 해 사용자 응답 경로에서 떼어내고, 그 비동기화가 만드는 문제들(응답 지연·동시 적재·풀 고갈·이중 요약)을 각각 다른 장치로 막는다. 특히 감상문에서 정당했던 **INSERT 재시도(@Retryable)는 컨텍스트 큐에서 제거**한다 — 전제가 성립하지 않고 공유 풀에 비용만 얹기 때문이다.

## 배경 — 두 큐의 적재 트리거는 성격이 다르다

작업 큐 골격(claim-with-SKIP-LOCKED, lease, 멱등 적재)은 감상문([0001] 원형)에서 가져와 공유한다. 하지만 **언제·누가·무엇을 위해 적재하는가**가 다르다.

| | 감상문 적재 (`EnqueueSummaryJobService`) | 컨텍스트 요약 적재 (`EnqueueContextSummaryJobService`) |
|---|---|---|
| 트리거 | 사용자가 감상문 생성을 **요청** | ASSISTANT 응답이 COMPLETED 로 커밋될 때마다 **자동** |
| 호출 맥락 | `SummaryDraftService`(`@Transactional`)에서 **동기**, 결과(`enqueued()`)로 흐름이 갈림 | `ContextSummarizeTriggerListener` 가 **AFTER_COMMIT + boundedElastic** 로 비동기 |
| 반환 | `EnqueueSummaryJobResult`(사용자 흐름이 소비) | `void`(fire-and-forget) |
| 실패의 의미 | 사용자 요청 실패 — 다음 기회 없음(one-shot) | 이번 턴만 거름 — 다음 턴이 다시 트리거(자가 치유) |
| 정확성 요구 | 지금 성공해야 함 | 결국(eventually) 되면 됨 |

컨텍스트 요약은 "미래 턴의 컨텍스트 조립을 위해 최근 원문을 요약으로 압축"하는 최적화다. 이번 턴에 적재가 밀려도 다음 턴에 다시 판정하며, 다음 턴이 없으면 조립할 대상도 없어 요약이 필요 없다. 이 **best-effort·자가 치유** 성격이 아래 결정들의 근거다.

## 결정

### 1. 적재를 AFTER_COMMIT + boundedElastic 로 비동기 offload

트리거 이벤트는 `AiChatMessagePersistService.saveAssistantSuccess`(`@Transactional`) 안에서 발행되고, 리스너는 `@TransactionalEventListener(AFTER_COMMIT)` 다. AFTER_COMMIT 콜백은 **커밋한 그 스레드(= 사용자에게 응답 Done 을 emit 하기 직전)**에서 동기로 돈다. 적재 작업(빠른 DB 판정 + 조건부 INSERT)을 그 자리에서 하면 **사용자 응답 완료가 그만큼 밀린다.** 적재는 사용자가 이미 받은 답변과 무관한 뒷정리이므로, `boundedElastic` 로 offload 해 커밋 스레드를 즉시 반환시킨다. 적재 실패는 로그만 남기고 던지지 않는다(다음 턴 자가 치유).

실제 LLM 요약 호출은 이 적재가 아니라 **워커가 별도 스케줄 풀에서** 비동기로 한다([0005]). 즉 적재 단계는 "짧은 DB 작업"만 한다.

### 2. 적재 판정은 "체크포인트 이후 원문 토큰 합 > 임계값" (매 턴)

체크포인트(`summarized_up_to_message_id`) 이후 원문 `token_count` 합을 `sumRecentMessageTokens` 로 구해 트리거 임계값과 비교한다. 이 판정 쿼리는 **요약을 하든 안 하든 매 대화 턴마다** 나가는 핫패스라, `V4` 인덱스 `(session_id, status, id)` 로 "세션 전체 스캔"이 아니라 "체크포인트 이후 꼬리만 스캔"이 되게 해 세션 길이와 무관하게 짧게 유지한다. 이 "짧음"이 결정 1(공유 풀에 얹어도 됨)의 전제다.

### 3. INSERT 재시도(@Retryable)를 제거 — 감상문과의 핵심 분기

감상문 적재에는 순간 락 실패(`CannotAcquireLockException`, #92)를 짧은 backoff 로 구제하는 `@Retryable` 이 있다. 컨텍스트 큐는 골격을 복제하며 이걸 딸려왔지만, **제거한다.** 근거 넷:

- **전제 불성립.** 감상문 재시도의 전제는 "사용자 대면 one-shot 이라 지금 실패하면 요청이 실패한다"다. 컨텍스트는 best-effort·매 턴 자가 치유라 이번 턴 실패가 다음 턴에 저절로 복구된다 — 재시도의 이득이 작다.
- **방어 대상이 이미 닫혔다.** 재시도가 막던 #92 의 gap lock ↔ INSERT 경합은, 워커 `claimOne` 의 **READ COMMITTED**([0005] 결정 5)로 이미 완화됐다. RC 에선 범위 스캔이 gap lock 을 걸지 않아 신규 PENDING INSERT 가 안 막힌다.
- **잔여 경합은 재시도 대상이 아니다.** RC 이후에도 남는 좁은 창은 `active_session_id` unique 인덱스의 동시 적재인데, 이건 대개 락 대기가 아니라 **중복키(`DataIntegrityViolationException`)**로 끝나고 catch 가 멱등 흡수한다(재시도 아님).
- **비용이 이득보다 크다.** `@Retryable` backoff 는 **sleep 으로 boundedElastic 스레드를 붙잡는다.** 게다가 이 증폭은 하필 **락 경합이 실제로 나는 순간** 발동하는데, 그때가 공유 풀이 가장 못 버티는 때라 사용자 영속화를 굶기는 위험과 상관된다. 이득 작고 비용은 최악의 순간에 몰린다 → 제거.

감상문 재시도는 **유지한다**(전제가 성립하니까).

### 4. 멱등·중복 방지는 재시도와 무관하게 유지

재시도를 떼도 "세션당 활성 작업 1개"와 "이중 요약 없음"은 그대로다 — 이 가드들은 재시도가 아니라 다른 장치가 지킨다:

- `active_session_id` **unique 제약** + `existsByActiveSessionId` precheck + `ContextSummaryJobInserter` 의 **REQUIRES_NEW** + catch 멱등. 트리거가 매 턴 울려도, 비동기로 여러 스레드가 겹쳐도, 세션당 활성 작업은 최대 1개.
- 워커 기록 단계의 **체크포인트 단조 전진 + 낙관적 version 검사**([0005] 결정 3): 낡은/중복 계산이 기록까지 와도 폐기된다.

### 5. REQUIRES_NEW 는 (지금은 없는) 호출자 트랜잭션까지 대비한 계약

현재 경로는 boundedElastic 에서 **주변 트랜잭션 없이** 돌아, unique 위반이 바깥을 오염시킬 일이 지금은 없다. 그럼에도 INSERT 를 REQUIRES_NEW 로 격리하는 이유는 ① 위반 시 그 INSERT 만 깔끔히 롤백돼 예외가 catch 로 온전히 전파되고, ② **누가 enqueue 를 트랜잭션 안에서 호출하더라도**(감상문 `SummaryDraftService` 가 실제로 그렇게 한다) 그 바깥 트랜잭션을 오염시키지 않기 때문이다. 오케스트레이터에 `@Transactional` 을 붙이지 않는 것과 짝을 이뤄, 바깥 트랜잭션 유무와 무관하게 catch 가 커밋 단계에서 `UnexpectedRollbackException` 으로 되살아나지 않게 한다.

## 비동기가 만드는 문제 — 어느 장치가 어느 문제를 막나

| 문제(비동기화가 낳음) | 장치 |
|---|---|
| 사용자 응답 완료 지연 | AFTER_COMMIT + boundedElastic offload (결정 1) |
| 같은 세션 동시 적재(비동기라 여러 스레드가 겹침) | `active_session_id` unique + precheck + catch (결정 4) |
| unique 위반이 (미래) 호출자 트랜잭션 오염 | REQUIRES_NEW + 오케스트레이터 무-`@Transactional` (결정 5) |
| 매 턴 판정 쿼리가 세션 길이만큼 느려짐 | `V4` 인덱스로 체크포인트 이후만 스캔 (결정 2) |
| 순간 락 실패로 적재 유실 | best-effort 자가 치유(다음 턴 재트리거) — 재시도 불필요 (결정 3) |
| 자가 치유 + 청크 분할이 이중 요약을 낳을까 | 활성 1개 + 체크포인트 단조 전진 + version 검사 (결정 4) |

## 버린 / 미룬 대안

- **재시도 유지(감상문과 대칭)** — 대칭은 깔끔해 보이나, 전제(사용자 대면 one-shot)가 컨텍스트엔 없고 backoff 가 공유 풀 스레드를 무는 비용이 최악의 순간에 몰린다. 대칭보다 맥락별 득실이 우선(결정 3).
- **적재를 동기로(감상문처럼)** — 사용자 응답 스레드를 판정·INSERT 로 막는다. 컨텍스트 요약은 사용자와 무관한 뒷정리라 부적합(결정 1).
- **적재 전용 격벽 풀 + 락 타임아웃 단축 — 미룸.** 지금은 적재 태스크가 짧고(외부 호출 없음), 장기 블로커(제목 LLM·moderation HTTP)는 이미 격벽/가상 스레드로 빠졌으며(#103), 재시도 제거로 최악 점유의 증폭기도 없앴다. 그래도 락 경합 시 INSERT 의 **base 점유**(락 대기, 인프라 `innodb_lock_wait_timeout` 만큼)가 공유 boundedElastic 을 무는 취약점은 남는다. 방어 심층성으로는 트리거에 **제목 생성처럼 전용 소형 격벽**을 주고, 인프라에서 **락 타임아웃을 짧게** 두는 게 낫다. **되살릴 트리거**: 공유 boundedElastic 포화 또는 #92 식 적재 락 대기가 다시 관측되면. 근거만 남기고 후속으로 둔다.

## 곁가지 — 운영 규모

배포(dev)는 t3.small(vCPU 2). Reactor 전역 `boundedElastic` 기본 크기는 `10 × vCPU = 20` 스레드(명시 설정 없음). 이 20 스레드를 영속화(응답 Done 직전 JDBC)·채팅 응답 경로·이 요약 적재가 공유한다 — 결정 1·3 이 "적재 태스크를 짧게, 스레드를 오래 물지 않게" 유지해야 하는 이유.
