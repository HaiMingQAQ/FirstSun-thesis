-- 所有 keys 带相同租户 hash tag；一次完成限流、旧码废弃和 pending 新版本。
if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
local count = tonumber(redis.call('GET', KEYS[3]) or '0')
if count >= 10 then return 0 end
redis.call('SET', KEYS[2], '1', 'EX', 60)
count = redis.call('INCR', KEYS[3])
if count == 1 then redis.call('EXPIRE', KEYS[3], 300) end
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'version', ARGV[1], 'digest', ARGV[2], 'state', 'pending', 'attempts', '0')
redis.call('EXPIRE', KEYS[1], 30)
return 1
