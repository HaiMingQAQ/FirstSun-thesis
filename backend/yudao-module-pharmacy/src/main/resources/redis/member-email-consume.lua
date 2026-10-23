-- 与读取的版本和当前摘要同时比较；成功删除与比较处于同一原子操作。
if redis.call('HGET', KEYS[1], 'state') ~= 'active' then return 0 end
if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end
if redis.call('HGET', KEYS[1], 'digest') ~= ARGV[2] then
    local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
    if attempts >= 5 then redis.call('DEL', KEYS[1]) end
    return 0
end
redis.call('DEL', KEYS[1])
return 1
