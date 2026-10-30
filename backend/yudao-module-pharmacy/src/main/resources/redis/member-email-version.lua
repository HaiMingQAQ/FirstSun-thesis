if redis.call('HGET', KEYS[1], 'state') ~= 'active' then return nil end
return redis.call('HGET', KEYS[1], 'version')
