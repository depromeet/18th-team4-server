-- OpenAI 전역 게이트 — 프로젝트 × 모델별 토큰 버킷 두 개(요청 수 · 토큰 수)를 한 번에 판정한다.
-- 보충은 배경 작업이 아니라 "마지막 계산 시각부터 지금까지 지난 시간 × 보충 속도" 를 두드릴 때마다 더하는 방식이다.
-- 두 버킷 모두 자리가 있어야 통과하고, 그때만 뺀다. 자리가 없으면 아무것도 빼지 않고 기다릴 시간만 돌려준다.
--
-- KEYS[1] = 요청 수 버킷 (hash: tokens, ts)
-- KEYS[2] = 토큰 수 버킷 (hash: tokens, ts)
-- ARGV[1] = 현재 시각 epoch millis (앱에서 전달 — 스크립트 안 TIME 은 결정성 때문에 금지)
-- ARGV[2] = 요청 수 버킷 크기
-- ARGV[3] = 요청 수 보충 속도 (건/ms)
-- ARGV[4] = 토큰 수 버킷 크기
-- ARGV[5] = 토큰 수 보충 속도 (토큰/ms)
-- ARGV[6] = 이번 호출이 필요로 하는 토큰 수
-- ARGV[7] = 키 TTL millis (버킷이 가득 차고도 남는 시간 — 키가 없으면 가득 찬 것으로 본다)
--
-- 반환: 0 = 통과(두 버킷에서 뺐음), 양수 = 기다릴 millis(뺀 것 없음)
--
-- 필요량이 버킷 크기보다 큰 호출(긴 감상문 프롬프트 등)은 영영 못 지나가는 대신, 버킷이 가득 찼을 때 통과시키고
-- 음수로 빼 둔다. 그만큼 뒤따르는 호출이 더 기다리므로 평균 속도는 지켜진다.

local nowMillis = tonumber(ARGV[1])
local requestCapacity = tonumber(ARGV[2])
local requestRatePerMillis = tonumber(ARGV[3])
local tokenCapacity = tonumber(ARGV[4])
local tokenRatePerMillis = tonumber(ARGV[5])
local tokensNeeded = tonumber(ARGV[6])
local ttlMillis = tonumber(ARGV[7])

local function refilled(key, capacity, ratePerMillis)
    local stored = redis.call('HMGET', key, 'tokens', 'ts')
    local tokens = tonumber(stored[1])
    local lastMillis = tonumber(stored[2])
    if tokens == nil or lastMillis == nil then
        return capacity
    end
    local elapsed = nowMillis - lastMillis
    if elapsed < 0 then
        elapsed = 0
    end
    local result = tokens + elapsed * ratePerMillis
    if result > capacity then
        result = capacity
    end
    return result
end

local function waitMillisFor(tokens, needed, capacity, ratePerMillis)
    local threshold = needed
    if threshold > capacity then
        threshold = capacity
    end
    if tokens >= threshold then
        return 0
    end
    return math.ceil((threshold - tokens) / ratePerMillis)
end

local requestTokens = refilled(KEYS[1], requestCapacity, requestRatePerMillis)
local tokenTokens = refilled(KEYS[2], tokenCapacity, tokenRatePerMillis)

local waitMillis = math.max(
    waitMillisFor(requestTokens, 1, requestCapacity, requestRatePerMillis),
    waitMillisFor(tokenTokens, tokensNeeded, tokenCapacity, tokenRatePerMillis)
)

if waitMillis == 0 then
    requestTokens = requestTokens - 1
    tokenTokens = tokenTokens - tokensNeeded
end

-- 통과하지 못해도 보충한 상태는 저장한다 — 다음 호출이 같은 시간을 두 번 보충하지 않게 한다.
redis.call('HSET', KEYS[1], 'tokens', tostring(requestTokens), 'ts', tostring(nowMillis))
redis.call('PEXPIRE', KEYS[1], ttlMillis)
redis.call('HSET', KEYS[2], 'tokens', tostring(tokenTokens), 'ts', tostring(nowMillis))
redis.call('PEXPIRE', KEYS[2], ttlMillis)

return waitMillis
