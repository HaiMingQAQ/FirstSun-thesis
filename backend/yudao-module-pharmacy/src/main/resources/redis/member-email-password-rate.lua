-- 成功/失败均计一次；独立于验证码，邮箱5次/5分钟、IP20次/5分钟。
if tonumber(redis.call('GET', KEYS[1]) or '0') >= 5 then return 0 end
if tonumber(redis.call('GET', KEYS[2]) or '0') >= 20 then return 0 end
local emailCount = redis.call('INCR', KEYS[1])
if emailCount == 1 then redis.call('EXPIRE', KEYS[1], 300) end
local ipCount = redis.call('INCR', KEYS[2])
if ipCount == 1 then redis.call('EXPIRE', KEYS[2], 300) end
return 1
