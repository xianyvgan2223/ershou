-- =============================================================
-- 商品有效期：修正单位错误 + 24 小时 → 7 天
--
-- 背景：ItemService 里写的是 `DEFAULT_HOURS_VALID = 60 * 24`，名字是"小时"，
-- 用的却是 `plusMinutes(...)` —— 商品实际只挂 **24 小时**，而 schema 注释和文档
-- 一直写着 60 天，挂了很久没人发现（没有测试覆盖到）。
--
-- 现在统一成 **7 天**（`ershou.item.valid-days` 可配），到期由定时任务
-- （每小时 :30）置为「已过期」并下架，卖家可在「我的发布」一键重新上架。
--
-- 用法： mysql -u ershou -p ershou < sql/migration-item-validity.sql
-- 注意：第 3 步 ADD KEY 只能跑一次，重复执行会报 Duplicate key name。
-- =============================================================

-- 1. 注释对齐（原来写的是 60 天）
ALTER TABLE item
  MODIFY COLUMN expire_at DATETIME DEFAULT NULL COMMENT '商品有效期截止(默认发布后 7 天，见 ershou.item.valid-days)';

-- 2. 老数据按新规则重算：旧 expire_at 全是"发布 + 24 小时"，对今天的数据来说基本都已过期，
--    不重算的话新任务一上线就会把在架商品集体下架。
UPDATE item
SET expire_at = DATE_ADD(COALESCE(published_at, created_at), INTERVAL 7 DAY)
WHERE status IN (0, 1, 2, 3, 6);

-- 3. 定时任务每小时按 (status, expire_at) 扫一遍
ALTER TABLE item ADD KEY idx_item_expire (status, expire_at);
