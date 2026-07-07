-- V5: 제목이 비어 있는 기존 세션에 기본 제목을 채운다.
-- 제목 생성은 첫 응답 후 비동기라, 생성 전/실패 시 title 이 NULL 로 남아 빈 제목이 노출됐다.
-- 앞으로 생성되는 세션은 AiChatSession.create() 가 기본 제목("새로운 대화")을 박지만,
-- 이미 만들어진 세션은 값이 없으므로 여기서 한 번 메꾼다. 생성에 성공한 세션(실제 제목 보유)은 건드리지 않는다.
UPDATE ai_chat_session
SET title = '새로운 대화'
WHERE title IS NULL
   OR title = '';
