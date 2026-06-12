-- 이슈 #69: 감상문 생성 완료 후 대화 이어가기 — 배포 시 데이터 마이그레이션
-- 적용 시점: 새 코드가 dev 에 머지·배포된 직후 (배포와 한 묶음으로 실행)
-- 적용 대상: dev RDS (readum)
-- 참고: dev RDS 를 초기화/재생성하는 경우 이 마이그레이션은 불필요 (새 스키마·데이터로 시작).
--
-- 새 코드는 세션 상태를 ACTIVE/LOCKED 로 읽는다. 구 모델의 'CLOSED' 값이 남아 있으면
-- 해당 세션 조회 시 enum 매핑 오류가 나므로, 초기화하지 않는 경우 배포 직후 반드시 실행해야 한다.

-- 1) 구 모델이 남긴 IN_PROGRESS 감상문 → FAILED
--    (생성 도중 프로세스가 죽어 영구 고착된 행. 새 모델에는 IN_PROGRESS 상태가 없다)
UPDATE summary
   SET status = 'FAILED'
     , updated_at = NOW()
 WHERE status = 'IN_PROGRESS';

-- 2) 구 모델의 '종료된' 세션 → 전부 ACTIVE
--    (새 모델에서 감상문 생성이 끝난 세션은 대화 가능 세션이다)
UPDATE ai_chat_session
   SET status = 'ACTIVE'
     , updated_at = NOW()
 WHERE status = 'CLOSED';

-- 검증: 두 쿼리 모두 0 이어야 한다.
-- SELECT COUNT(*) FROM summary WHERE status = 'IN_PROGRESS';
-- SELECT COUNT(*) FROM ai_chat_session WHERE status NOT IN ('ACTIVE', 'LOCKED');
