-- 清理失败请求自己的版本，禁止误删后来重发的验证码。
if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end
return redis.call('DEL', KEYS[1])
