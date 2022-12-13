local voucherId = ARGV[1]
local orderId = ARGV[2]
local userId = ARGV[3]

local orderKey = KEYS[1] .. voucherId
local stockKey = KEYS[2] .. voucherId

if (tonumber(redis.call("get", stockKey)) <= 0) then
    -- 库存不足 返回1
    return 1
end

if (redis.call("sismember", orderKey, userId) == 1) then
    -- 重复下单 返回2
    return 2
end
-- 扣库存
redis.call("incrby", stockKey, -1)
-- 将用户保存到已下单集合中
redis.call("sadd", orderKey, userId)

-- 成功 返回0
return 0