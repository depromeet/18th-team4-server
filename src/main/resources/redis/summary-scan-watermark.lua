-- 감상문 자동 적재가 마지막으로 <b>성공한</b> 스캔 시각. 건너뛴 회차의 대상을 다음 회차가 이어 담게 하는 표식이다.
--
-- 값은 epoch millis 숫자로 둔다 — 여러 서버가 동시에 전진시킬 때 "뒤로 가지 않는다" 를 비교로 판정해야 하는데,
-- 문자열 시각은 자릿수가 들쭉날쭉해(초·나노가 0 이면 생략된다) 사전식 비교를 믿을 수 없기 때문이다.
--
-- KEYS[1] = 표식 키
-- ARGV[1] = 연산 (init | advance)
-- ARGV[2] = 값 epoch millis (init 이면 기준점, advance 면 이번에 성공한 시각)
-- ARGV[3] = 보관 기간 millis
--
-- 반환: 반영 후의 표식 값(epoch millis 문자열)

local key = KEYS[1]
local operation = ARGV[1]
local value = tonumber(ARGV[2])
local ttlMillis = tonumber(ARGV[3])

local stored = tonumber(redis.call('GET', key))

if operation == 'init' then
    -- 없을 때만 심는다. 이미 있으면 그 값이 정본이다 — 첫 회차가 장애로 거절돼도 기준점은 남아,
    -- 다음 성공 회차가 그 지점부터 훑어 건너뛴 구간을 메운다.
    if stored == nil then
        stored = value
        redis.call('SET', key, tostring(value))
    end
    redis.call('PEXPIRE', key, string.format('%d', ttlMillis))
    return tostring(stored)
end

if operation == 'advance' then
    -- 뒤로 가지 않는다. 여러 서버가 겹쳐 돌 때 늦게 끝난 쪽이 앞선 표식을 되돌리면 같은 구간을 다시 훑는다.
    if stored == nil or value > stored then
        stored = value
        redis.call('SET', key, tostring(value))
    end
    redis.call('PEXPIRE', key, string.format('%d', ttlMillis))
    return tostring(stored)
end

return redis.error_reply('알 수 없는 연산: ' .. tostring(operation))
