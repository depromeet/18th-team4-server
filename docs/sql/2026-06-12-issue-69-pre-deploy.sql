-- 이슈 #69: 감상문 생성 완료 후 대화 이어가기 — 사전 마이그레이션
-- 적용 시점: feature 브랜치 개발/테스트 시작 전 (코드 배포와 무관하게 먼저 실행 가능)
-- 적용 대상: dev RDS (readum)
--
-- 새 모델은 세션당 감상문(summary) 행을 여러 건 허용한다 (재생성 이력 보존).
-- DAO 통합 테스트가 dev RDS 에 직접 접속해 세션당 복수 행을 저장하므로,
-- 이 제약 제거가 선행되어야 테스트가 통과한다.
-- 구 코드는 세션당 두 번째 행을 만들지 않으므로 이 변경은 구 코드와 호환된다.

ALTER TABLE summary DROP INDEX uk_summary_ai_chat_session;

-- 검증: 아래 결과에 uk_summary_ai_chat_session 이 없어야 한다.
-- SHOW INDEX FROM summary;
