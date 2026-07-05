# 도메인 지도

readum 의 기능이 어느 도메인에 있는지 찾는 색인. 상세는 각 도메인 문서로.

**이 디렉토리의 문서 채택 기준** — 표준 경로(Controller → Service →
Repository, CLAUDE.md 4계층)를 그대로 타는 기능은 컨벤션이 문서를 대신하므로
따로 설명하지 않는다. 도메인 문서에 적는 것은 코드가 말할 수 없는 것들이다:
설계 의도(왜), 도메인 경계와 배치 근거, 불변식, 표준 경로를 벗어나는
흐름(비동기·크로스 도메인·사고 이력). 엔드포인트별 요청/응답 계약은 Swagger
(`@Operation`)가 원천이다.

## auth — 인증

세션 쿠키 기반 인증. 신원 해석은 `SessionCookieAuthenticationFilter` 가
전담하고 컨트롤러에는 `@AuthenticatedUserId Long userId` 로만 전달된다.

| 기능 | API |
|---|---|
| Access Token 재발급 | `POST /api/v1/auth/refresh` |
| 로그아웃 | `POST /api/v1/auth/logout` |

문서: (미작성) · 패키지: `domain/auth`, `infrastructure/security`,
`presentation/common/security`

## user — 회원

가입(세션 생성)·온보딩·프로필.

| 기능 | API |
|---|---|
| 사용자 세션 생성 | `POST /api/v1/users/sessions` |
| 현재 세션 정보 조회 | `GET /api/v1/users/me` |
| 내 프로필 조회 | `GET /api/v1/users/me/profile` |
| 온보딩 완료 처리 | `POST /api/v1/users/me/onboarding` |
| 닉네임 수정 | `PUT /api/v1/users/me/nickname` |

문서: (미작성) · 패키지: `domain/user`, `model/user`

## user/userbook — 내 책장

사용자가 등록한 책. user 도메인 하위 패키지에 있다 (중간 엔티티 배치 규칙은
Epic #117 이관 심사 대상).

| 기능 | API |
|---|---|
| 내 책장 도서 추가 | `POST /api/v1/user-books` |
| 내 책장 도서 목록 조회 | `GET /api/v1/user-books` |
| 내 책장 도서 삭제 | `DELETE /api/v1/user-books/{userBookId}` |

문서: (미작성) · 패키지: `domain/user/userbook`

## book — 도서 검색

알라딘 API 를 통한 도서 검색·조회. 자체 저장 데이터보다 외부 API 프록시
성격이 강하다.

| 기능 | API |
|---|---|
| 키워드 도서 검색 | `GET /api/v1/books` |

문서: (미작성) · 패키지: `domain/book`, `infrastructure/book/aladin`

## aiChat — AI 채팅

책 한 권에 대한 AI 대화 세션·메시지. 감상문의 사용자 대면 부분(요청 접수·
자격 판정·편집) 포함.

| 기능 | API |
|---|---|
| 세션 생성 | `POST /api/v1/ai-chat/sessions` |
| 세션 목록 조회 | `GET /api/v1/ai-chat/sessions` |
| 책별 세션 목록 조회 | `GET /api/v1/ai-chat/books/{userBookId}/sessions` |
| 메시지 전송 (SSE 스트리밍) | `POST /api/v1/ai-chat/sessions/{sessionId}/messages` |
| 메시지 이력 조회 | `GET /api/v1/ai-chat/sessions/{sessionId}/messages` |
| 감상문 조회 | `GET /api/v1/ai-chat/sessions/{sessionId}/summary` |
| 감상문 수정 | `PUT /api/v1/ai-chat/sessions/{sessionId}/summary` |
| 감상문 초안 생성 가능 여부 | `GET /api/v1/ai-chat/sessions/{sessionId}/summary-draft/eligibility` |
| 감상문 초안 생성 요청 | `POST /api/v1/ai-chat/sessions/{sessionId}/summary-draft` |
| (백그라운드) 세션 제목 자동 생성 | 첫 응답 커밋 후 이벤트 |

문서: [ai-chat.md](ai-chat.md) · 패키지: `domain/aiChat`, `model/aiChat`,
`infrastructure/ai`

## summary — 감상 기록

확정된 감상문의 조회·이력과, 감상문 생성 파이프라인의 실행(작업 큐·워커).
생성 요청의 접수는 aiChat 소관 — 경계 근거는
[ai-chat.md 의 경계 절](ai-chat.md#경계) 참조.

| 기능 | API |
|---|---|
| 내 감상 기록 목록 조회 | `GET /api/v1/summaries` |
| 월별 독서 기록 조회 (홈 캘린더) | `GET /api/v1/summaries/calendar` |
| 감상 기록 상세 조회 | `GET /api/v1/summaries/{summaryId}` |
| (백그라운드) 감상문 자동 생성 적재 | 매일 06:00 스케줄러 |
| (백그라운드) 생성 작업 실행·고아 회수 | 2초 간격 디스패처 / 60초 리퍼 |

문서: (미작성) · 패키지: `domain/summary`, `model/summary`,
`infrastructure/summary`
