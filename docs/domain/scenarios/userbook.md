# 내 책장 (userbook) 시나리오

## 1. 내 책장 도서 추가

로그인한 사용자가 외부 도서 식별자(ISBN13)로 도서를 책장에 추가한다. Book 마스터가 없으면 알라딘 조회 결과로 생성(upsert)하고, 이미 책장에 있는 도서면 409 를 반환한다.

```mermaid
flowchart TD
    A["POST /api/v1/user-books (bookExternalId)"] --> B{"인증 통과? (@AuthenticatedUserId 로 userId 전달)"}
    B -- 아니오 --> B401["401 Unauthorized"]
    B -- 예 --> C{"요청 검증: bookExternalId @NotBlank"}
    C -- 실패 --> C400["400 Bad Request"]
    C -- 통과 --> D["UserBookCreateService.execute — @Transactional"]
    D --> E["BookLookupClient.execute — 알라딘 ItemLookUp 도서 조회 (book 도메인)"]
    E -- 조회 결과 없음 --> E404["404 LOOKUP_NOT_FOUND"]
    E -- 알라딘 5xx 응답 --> E502["502 LOOKUP_GATEWAY_ERROR"]
    E -- 응답 시간 초과 --> E504["504 LOOKUP_TIMEOUT"]
    E -- 네트워크 오류 --> E502b["502 LOOKUP_IO_FAILURE"]
    E -- 그 외 호출 실패 --> E500["500 LOOKUP_FAILED"]
    E -- 성공 --> F["BookRepository.upsert — INSERT ... ON DUPLICATE KEY UPDATE, external_id unique 라 동시 요청에도 Book 마스터 1건만 유지"]
    F --> G["BookRepository.findByExternalId 로 Book 엔티티 확보"]
    G --> H["UserBookRepository.save — UserBook.create(userId, bookId)"]
    H -- 저장 성공 --> I["201 Created + Location 헤더, UserBookCreateResult.from(saved, book)"]
    H -- "DataIntegrityViolationException — uk_user_book_user_book(user_id, book_id) unique 제약 위반 (재등록 또는 동시 요청 race)" --> J["UserBookConflictReader.find — 새 트랜잭션(REQUIRES_NEW)으로 기존 UserBook 재조회"]
    J -- 기존 행 있음 --> K["409 Conflict ALREADY_EXISTS — 기존 도서 정보를 응답 data 에 함께 반환 (ConflictException payload)"]
    J -- 기존 행도 없음 --> L["원래 DataIntegrityViolationException 다시 던짐 — DataAccessException 핸들러가 503 으로 응답"]
```

## 2. 내 책장 목록 조회

로그인한 사용자가 책장에 등록한 도서 목록을 최근 등록순으로 조회한다. 도서마다 연결된 AI 대화 세션 수를 함께 내려준다.

```mermaid
flowchart TD
    A["GET /api/v1/user-books"] --> B{"인증 통과? (@AuthenticatedUserId 로 userId 전달)"}
    B -- 아니오 --> B401["401 Unauthorized"]
    B -- 예 --> C["UserBookSearchService.findMyBooks(userId)"]
    C --> D["UserBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc — UserBook 과 Book join projection + 도서별 AiChatSession 수(chatSessionCount) 서브쿼리 집계"]
    D --> E["UserBookSearchItemResult.from 으로 변환 — 정렬은 createdAt desc, id desc (최근 등록순)"]
    E --> F["200 OK — 등록 도서 0건이면 빈 배열"]
```

## 3. 내 책장 도서 삭제

로그인한 사용자가 자기 책장의 도서를 삭제하면, 그 도서를 가리키는 연관 데이터(AI 대화 메시지·감상문 생성 작업·세션·감상 기록·마지막 선택 도서 참조)를 한 트랜잭션에서 순서대로 함께 삭제한다. DB FK/JPA cascade 없이 서비스 계층이 명시 삭제한다.

```mermaid
flowchart TD
    A["DELETE /api/v1/user-books/{userBookId}"] --> B{"인증 통과? (@AuthenticatedUserId 로 userId 전달)"}
    B -- 아니오 --> B401["401 Unauthorized"]
    B -- 예 --> C["UserBookDeleteService.execute — @Transactional 한 트랜잭션"]
    C --> D{"UserBookRepository.findByIdAndUserId — 본인 소유 검증"}
    D -- "없음 (미존재와 타인 소유를 구분하지 않음 — 타인 도서 존재 여부 누설 방지)" --> D404["404 NOT_FOUND"]
    D -- 있음 --> E["AiChatMessageRepository.deleteAllByUserBookId — 세션 서브쿼리로 대화 메시지 삭제, 세션보다 먼저 (ai-chat 도메인)"]
    E --> F["SummaryJobRepository.deleteAllByUserBookId — 세션 서브쿼리로 감상문 생성 작업 삭제, 세션보다 먼저 (summary 도메인)"]
    F --> G["AiChatSessionRepository.deleteAllByUserBookId — 대화 세션 삭제 (ai-chat 도메인)"]
    G --> H["SummaryRepository.deleteAllByUserBookId — 감상 기록 삭제, userBookId 직접 보유 (summary 도메인)"]
    H --> I["UserRepository.clearLastSelectedUserBook — 이 도서를 마지막 선택 도서로 가리키던 사용자의 lastSelectedUserBookId 를 null 로 정리 (user 도메인)"]
    I --> J["UserBookRepository.deleteById — UserBook 본체 삭제. 여러 사용자가 공유하는 Book 마스터는 삭제하지 않음"]
    J --> K["204 No Content"]
```
