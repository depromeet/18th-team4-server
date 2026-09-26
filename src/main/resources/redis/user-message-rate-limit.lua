-- 사용자 메시지 폭주 가드 — ZSET 슬라이딩 윈도우.
-- 검사(창 내 건수)와 기록(이번 요청 추가)을 한 스크립트에서 원자로 수행한다.
-- 앱의 검사와 저장 사이 간격을 노려 동시 요청이 전부 통과하던 경쟁을 막는 것이 목적.
--
-- KEYS[1] = ai-chat:rate-limit:{userId}
-- ARGV[1] = 현재 시각 epoch millis (앱에서 전달 — 스크립트 안 TIME 은 결정성 때문에 금지)
-- ARGV[2] = 창 길이 millis
-- ARGV[3] = 창 내 허용 최대 건수
-- ARGV[4] = 이번 요청의 member (요청마다 유일한 값)
--
-- 반환: 1 = 통과(기록됨), 0 = 한도 초과(기록 안 함)
local nowMillis = tonumber(ARGV[1])
local windowMillis = tonumber(ARGV[2])
local maxCount = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowMillis - windowMillis)
local countInWindow = redis.call('ZCARD', KEYS[1])
if countInWindow < maxCount then
    redis.call('ZADD', KEYS[1], nowMillis, ARGV[4])
    redis.call('PEXPIRE', KEYS[1], windowMillis)
    return 1
end
return 0
