-- Shared Java/Python quota protocol v2. All keys use the same Redis Cluster hash tag.
-- KEYS: project requests, token reservations, project day, global requests, global day, state, metrics.
-- ARGV: operation, tokens, RPM, TPM, RPD, global RPM, global RPD, request ID,
--       Pacific day start/end (epoch ms), failure threshold, circuit delay ms, retry delay ms.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local op = ARGV[1]
local threshold = tonumber(ARGV[11])
local circuit = tonumber(ARGV[12])
if op == 'success' then
    -- Older successful calls must not clear a concurrent 429 cooldown.
    redis.call('HDEL', KEYS[6], 'failures')
    redis.call('HINCRBY', KEYS[7], 'success', 1)
elseif op == '429' then
    local untilTime = now + tonumber(ARGV[13])
    local old = tonumber(redis.call('HGET', KEYS[6], 'until') or '0')
    redis.call('HSET', KEYS[6], 'until', math.max(old, untilTime))
    redis.call('HINCRBY', KEYS[7], '429', 1)
elseif op == 'failure' then
    local failures = redis.call('HINCRBY', KEYS[6], 'failures', 1)
    if failures >= threshold then
        local old = tonumber(redis.call('HGET', KEYS[6], 'until') or '0')
        redis.call('HSET', KEYS[6], 'until', math.max(old, now + circuit))
    end
    redis.call('HINCRBY', KEYS[7], 'failure', 1)
end
if op ~= 'reserve' and op ~= 'status' then
    redis.call('PEXPIRE', KEYS[6], math.max(circuit, tonumber(ARGV[13])) + 86400000)
    redis.call('EXPIRE', KEYS[7], 172800)
    return {1, 0}
end
local dayStart, dayEnd = tonumber(ARGV[9]), tonumber(ARGV[10])
if now < dayStart or now >= dayEnd then return {0, 1000} end
local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now - 60000)
for _, id in ipairs(expired) do redis.call('HDEL', KEYS[2], id) end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 60000)
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now - 60000)
local rpm, globalRpm = redis.call('ZCARD', KEYS[1]), redis.call('ZCARD', KEYS[4])
local tpm = 0
for _, value in ipairs(redis.call('HVALS', KEYS[2])) do tpm = tpm + tonumber(value) end
local rpd = tonumber(redis.call('GET', KEYS[3]) or '0')
local globalRpd = tonumber(redis.call('GET', KEYS[5]) or '0')
local cooldown = tonumber(redis.call('HGET', KEYS[6], 'until') or '0')
if op == 'status' then return {rpm, tpm, rpd, globalRpm, globalRpd, math.max(0, cooldown - now)} end
local wait = math.max(0, cooldown - now)
local function minuteWait(key)
    local first = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    if #first > 0 then return math.max(1, tonumber(first[2]) + 60000 - now) end
    return 60000
end
local tokens = tonumber(ARGV[2])
if tonumber(ARGV[3]) > 0 and rpm >= tonumber(ARGV[3]) then wait = math.max(wait, minuteWait(KEYS[1])) end
if tonumber(ARGV[4]) > 0 and tpm + tokens > tonumber(ARGV[4]) then wait = math.max(wait, 60000) end
if tonumber(ARGV[5]) > 0 and rpd >= tonumber(ARGV[5]) then wait = math.max(wait, dayEnd - now) end
if tonumber(ARGV[6]) > 0 and globalRpm >= tonumber(ARGV[6]) then wait = math.max(wait, minuteWait(KEYS[4])) end
if tonumber(ARGV[7]) > 0 and globalRpd >= tonumber(ARGV[7]) then wait = math.max(wait, dayEnd - now) end
if wait > 0 then
    redis.call('HINCRBY', KEYS[7], 'rejected', 1)
    redis.call('EXPIRE', KEYS[7], 172800)
    return {0, wait}
end
local id = ARGV[8]
redis.call('ZADD', KEYS[1], now, id)
redis.call('HSET', KEYS[2], id, tokens)
redis.call('ZADD', KEYS[4], now, id)
redis.call('INCR', KEYS[3])
redis.call('INCR', KEYS[5])
for _, index in ipairs({1, 2, 4}) do redis.call('PEXPIRE', KEYS[index], 61000) end
for _, index in ipairs({3, 5}) do redis.call('PEXPIREAT', KEYS[index], dayEnd + 86400000) end
redis.call('HINCRBY', KEYS[7], 'allowed', 1)
redis.call('EXPIRE', KEYS[7], 172800)
return {1, 0}
