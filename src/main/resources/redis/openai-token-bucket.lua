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
-- ARGV[7] = 키 최소 TTL millis (버킷이 가득 차고도 남는 시간 — 키가 없으면 가득 찬 것으로 본다)
--
-- 반환: 0 = 통과(두 버킷에서 뺐음), 양수 = 기다릴 millis(뺀 것 없음)
--
-- 필요량이 버킷 크기보다 큰 호출(긴 감상문 프롬프트 등)은 영영 못 지나가는 대신, 버킷이 가득 찼을 때 통과시키고
-- 음수로 빼 둔다. 그만큼 뒤따르는 호출이 더 기다리므로 평균 속도는 지켜진다.
-- 그래서 실제 TTL 은 ARGV[7] 과 "지금 잔량에서 가득 차기까지 걸리는 시간" 중 긴 쪽이다 — 빚진 버킷이 먼저 만료되면
-- 다음 호출이 가득 찬 버킷을 새로 만들어 빚이 사라지기 때문이다.

local nowMillis = tonumber(ARGV[1])
local requestCapacity = tonumber(ARGV[2])
local requestRatePerMillis = tonumber(ARGV[3])
local tokenCapacity = tonumber(ARGV[4])
local tokenRatePerMillis = tonumber(ARGV[5])
local tokensNeeded = tonumber(ARGV[6])
local minimumTtlMillis = tonumber(ARGV[7])

-- 보충한 잔량과, 저장할 시각을 함께 돌려준다.
local function refilled(key, capacity, ratePerMillis)
    local stored = redis.call('HMGET', key, 'tokens', 'ts')
    local tokens = tonumber(stored[1])
    local lastMillis = tonumber(stored[2])
    if tokens == nil or lastMillis == nil then
        return capacity, nowMillis
    end
    local elapsed = nowMillis - lastMillis
    if elapsed < 0 then
        elapsed = 0
    end
    local result = tokens + elapsed * ratePerMillis
    if result > capacity then
        result = capacity
    end
    -- 시계가 뒤로 간 호출이 저장된 시각까지 되돌리지는 않게 한다 — 되돌리면 다음 호출이 이미 보충한 시간을 다시 보충한다.
    return result, math.max(nowMillis, lastMillis)
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

-- 빚진(음수) 버킷은 다 갚을 때까지 살려 둔다 — 중간에 만료되면 다음 호출이 가득 찬 버킷을 새로 만들어 빚이 없어진다.
local function store(key, tokens, timestampMillis, capacity, ratePerMillis)
    redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(timestampMillis))
    local millisToFull = math.ceil((capacity - tokens) / ratePerMillis)
    redis.call('PEXPIRE', key, string.format('%d', math.max(minimumTtlMillis, millisToFull)))
end

local requestTokens, requestTimestampMillis = refilled(KEYS[1], requestCapacity, requestRatePerMillis)
local tokenTokens, tokenTimestampMillis = refilled(KEYS[2], tokenCapacity, tokenRatePerMillis)

local waitMillis = math.max(
    waitMillisFor(requestTokens, 1, requestCapacity, requestRatePerMillis),
    waitMillisFor(tokenTokens, tokensNeeded, tokenCapacity, tokenRatePerMillis)
)

if waitMillis == 0 then
    requestTokens = requestTokens - 1
    tokenTokens = tokenTokens - tokensNeeded
end

-- 통과하지 못해도 보충한 상태는 저장한다 — 다음 호출이 같은 시간을 두 번 보충하지 않게 한다.
store(KEYS[1], requestTokens, requestTimestampMillis, requestCapacity, requestRatePerMillis)
store(KEYS[2], tokenTokens, tokenTimestampMillis, tokenCapacity, tokenRatePerMillis)

return waitMillis
