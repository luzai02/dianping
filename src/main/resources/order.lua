--- 判断库存是否充足 && 判断用户是否已下单
--- @param ARGV[1] voucherId 优惠券ID
--- @param ARGV[2] userId 用户ID
--- @return number 0:成功 1:库存不足 2:用户已下单

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 获取库存
local stock = redis.call('GET', stockKey)
if not stock or tonumber(stock) <= 0 then
    return 1 -- 库存不足
end

-- 检查用户是否已经下单
if redis.call('SISMEMBER', orderKey, userId) == 1 then
    return 2 -- 用户已下单
end

-- 扣减库存
redis.call('DECR', stockKey)
-- 添加用户到下单集合
redis.call('SADD', orderKey, userId)

-- 发送消息
redis.call('XADD', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0 -- 下单成功
