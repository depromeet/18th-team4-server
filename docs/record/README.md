# 의사결정 기록 (decision record)

이 디렉토리는 **"왜 이렇게 결정했는가"** 를 남기는 곳이다. 코드 diff 만으로는 드러나지 않는
선택의 맥락·근거·버린 대안을 기록해, 나중에 같은 고민을 반복하거나 무심코 되돌리는 일을 막는다.

- 코드가 *무엇* 을 하는지는 코드와 주석이 설명한다. 여기엔 *왜* 그 코드인지를 적는다.
- 파일명: `NNNN-짧은-영문-슬러그.md` (4자리 일련번호). 한 결정 = 한 파일.
- 한번 채택된 기록은 함부로 고치지 않는다. 결정이 바뀌면 새 기록을 추가하고 옛 기록의 상태를 `대체됨`으로 바꾼다.

## 목록

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-aichat-title-generation-scheduler-bulkhead.md) | 제목 생성 전용 스케줄러 분리 (격벽) | 채택 |
| [0002](0002-chat-data-scale-storage-strategy.md) | 대용량 채팅 데이터 저장/조회·쓰기 전략 (현재는 MySQL 유지) | 채택 |
| [0003](0003-aichat-concurrency-moderation-http-client.md) | AI 채팅 동시성 — moderation HTTP 클라이언트 구조 + OSIV/virtual thread 전환 | 채택 |
