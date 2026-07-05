# architecture — 서버 기술 구조

서버가 어떻게 짜여 있는지 — 계층 구조, 외부 시스템 연동, 인증 구조 — 를 설명한다.
기능별 도메인 정책·시나리오는 [docs/domain/](../domain/README.md) 소관이다.

| 파일 | 내용 |
|------|------|
| [system-overview.md](system-overview.md) | 기술 스택·모듈 구성·실행 모델·계층 구조·데이터 저장·백그라운드 처리 |
| [external-integrations.md](external-integrations.md) | OpenAI·알라딘 도서 API·Caffeine 캐시 연동, Port/Adapter 목록 |
| [security-architecture.md](security-architecture.md) | 세션 쿠키 인증 구조와 AI 채팅 rate limit |
