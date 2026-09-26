-- V2: 메시지별 토큰 크기(조립·요약 트리거용). USER=jtokkit 로컬 계산, ASSISTANT=API 실측 출력.
-- 기존 호출 단위 usage(input/output/total_tokens)와 축이 다른 균일 컬럼. 기존 데이터 backfill 없음(드랍 확인).
ALTER TABLE ai_chat_message
    ADD COLUMN token_count INT NULL AFTER total_tokens;
