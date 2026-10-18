-- 秒杀抢购原子扣减（第 2 轮平台代卖 · 设计第四节）
--
-- 与 coupon_claim.lua 判定逻辑完全一致，只是 key 换成秒杀库存 / 已购 Hash。
-- 分开成两个文件：后续券与秒杀如果想引入不同规则（例如秒杀按场次限购、券按券种限领），
-- 各自改各自的脚本，不会互相影响。
--
-- KEYS[1] = 秒杀剩余库存 key（String/int）
-- KEYS[2] = 已购用户 Hash key（field=userId，value=已购件数）
-- ARGV[1] = userId
-- ARGV[2] = 每人限购件数
--
-- 返回：1 成功 / 0 已抢完 / -1 未预热（stock key 不存在）/ -2 超限

local stock = redis.call('GET', KEYS[1])
if not stock then
  return -1
end
if tonumber(stock) <= 0 then
  return 0
end
local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if bought >= tonumber(ARGV[2]) then
  return -2
end
redis.call('DECR', KEYS[1])
redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
return 1
