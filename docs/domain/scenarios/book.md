# 도서 검색 (book) 시나리오

## 1. 키워드 도서 검색

사용자가 검색어로 외부(알라딘 ItemSearch API) 도서를 검색해 페이지네이션 결과를 받는다.

```mermaid
flowchart TD
    A["GET /api/v1/books?keyword&page&size"] --> B["BookSearchRequest 검증 (@Valid)"]
    B -->|"keyword 2자 미만/공백, page 1 미만, size 1~50 벗어남"| B1["400 응답 — 요청 값 검증 실패"]
    B -->|통과| C["toCommand() — page 생략 시 1, size 생략 시 30"]
    C --> D["BookSearchService.search(command)"]
    D --> E["AladinBookSearchClientImpl.execute"]
    E --> F{"start = (page-1)*size+1 이 searchResultLimit(200) 초과?"}
    F -->|초과| G["알라딘을 호출하지 않고 빈 결과 반환 (books=[], totalResultCount=0, hasNext=false)"]
    G --> H["200 응답 — BookSearchResponse"]
    F -->|이하| I["알라딘 ItemSearch.aspx 호출 (RestClient, 연결/읽기 타임아웃 = requestTimeout, dev 설정 1초)"]
    I -->|정상 응답| J{"응답 본문이 null?"}
    J -->|null| G
    J -->|있음| K["totalResults 를 searchResultLimit(200) 으로 상한 적용, item 목록 → BookResult 변환, hasNext = page*size < 상한 적용된 총 건수"]
    K --> H
    I -->|"HttpServerErrorException (알라딘 5xx 응답)"| L["BadGatewayException(SEARCH_GATEWAY_ERROR) → 502 응답"]
    I -->|"ResourceAccessException (타임아웃, 네트워크 IO 실패)"| M["GatewayTimeoutException(SEARCH_TIMEOUT) → 504 응답"]
    I -->|"그 외 RestClientException (4xx 등)"| N["ExternalApiException(SEARCH_FAILED) → 500 응답"]
```

## 2. 도서 상세 확보 (BookLookupClient)

내 책장에 도서를 등록할 때(userbook 진입) `UserBookCreateService` 가 ISBN13 으로 알라딘 ItemLookUp API 에서 도서 원천 정보를 확보한다. 확보 이후의 등록 흐름은 userbook 문서 소관.

```mermaid
flowchart TD
    A["(userbook 진입) UserBookCreateService.execute — 도서 등록 시작"] --> B["BookLookupClient.execute(isbn13)"]
    B --> C["AladinBookLookupClientImpl — 알라딘 ItemLookUp.aspx 호출 (ItemIdType=ISBN13, 연결/읽기 타임아웃 = requestTimeout, dev 설정 1초)"]
    C -->|정상 응답| D{"응답이 null 이거나 item 목록이 비어 있음?"}
    D -->|비어 있음| E["NotFoundException(LOOKUP_NOT_FOUND) → 404 응답"]
    D -->|있음| F["item 첫 번째 항목 → BookResult 변환 (cover 공백이면 null, pubDate 에서 발행연도 추출)"]
    F --> G["BookResult 반환 → 이후 Book UPSERT 등 등록 흐름은 userbook 문서 소관"]
    C -->|"HttpServerErrorException (알라딘 5xx 응답)"| H["BadGatewayException(LOOKUP_GATEWAY_ERROR) → 502 응답"]
    C -->|"ResourceAccessException — 원인에 SocketTimeoutException 포함"| I["GatewayTimeoutException(LOOKUP_TIMEOUT) → 504 응답"]
    C -->|"ResourceAccessException — 그 외 네트워크 IO 실패"| J["BadGatewayException(LOOKUP_IO_FAILURE) → 502 응답"]
    C -->|"그 외 RestClientException"| K["ExternalApiException(LOOKUP_FAILED) → 500 응답"]
```
