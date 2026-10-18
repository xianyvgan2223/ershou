-- 抢券原子扣减（第 2 轮平台代卖 · 设计第四节）
--
-- 为什么用 Lua：库存判定（stock<=0）与扣减、限领判定（HGET）与 +1 必须是原子的，
-- 否则并发下 10 个请求都读到 stock=1，最后超卖。
--
-- KEYS[1] = 券剩余库存 key（String/int）
-- KEYS[2] = 已领用户 Hash key（field=userId，value=已领张数）
-- ARGV[1] = userId
-- ARGV[2] = 每人限领张数
--
-- 返回：1 成功 / 0 已抢完 / -1 未预热（stock key 不存在）/ -2 超限

local stock = redis.call('GET', KEYS[1])
if not stock then
  return -1
end
if tonumber(stock) <= 0 then
  return 0
end
local used = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if used >= tonumber(ARGV[2]) then
  return -2
end
redis.call('DECR', KEYS[1])
redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
return 1
