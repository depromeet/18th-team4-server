-- 이슈 #69: 감상문 생성 완료 후 대화 이어가기 — 사전 마이그레이션
-- 적용 시점: 새 코드 배포 전 아무 때나 (코드 배포와 무관하게 먼저 실행 가능). 단, 새 코드 배포 전에는 반드시 실행돼 있어야 한다.
-- 적용 대상: dev RDS (readum)
-- 참고: dev RDS 를 초기화/재생성하는 경우 이 마이그레이션은 불필요 (엔티티에서 제약이 제거되어 새 스키마에는 없음).
--
-- 새 코드는 감상문 재생성 시 세션당 summary 행을 여러 건 insert 한다 (재생성 이력 보존).
-- 이 unique 제약이 남아 있으면 ddl-auto: validate 는 통과하지만(unique 미검증),
-- 첫 재생성의 두 번째 행 insert 가 duplicate key 로 실패해 세션이 LOCKED 로 고착된다.
-- 구 코드는 세션당 두 번째 행을 만들지 않으므로 이 변경은 구 코드와 호환된다.

ALTER TABLE summary DROP INDEX uk_summary_ai_chat_session;

-- 검증: 아래 결과에 uk_summary_ai_chat_session 이 없어야 한다.
-- SHOW INDEX FROM summary;
