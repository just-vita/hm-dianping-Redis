-- 保证锁释放时的原子性
-- 比较锁标识是否正确
if (redis.call("get", KEYS[1]) == ARGV[1]) then
    -- 释放锁
    redis.call("del", KEYS[1])
end
return 0