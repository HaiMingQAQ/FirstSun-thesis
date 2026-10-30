-- 已过期或被重发替换的响应不能激活验证码。
if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end
if redis.call('HGET', KEYS[1], 'state') ~= 'pending' then return 0 end
redis.call('HSET', KEYS[1], 'state', 'active')
redis.call('EXPIRE', KEYS[1], 300)
return 1
