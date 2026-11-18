-- =============================================================
-- 第 6 轮全量拆分 · map-service 数据库初始化与数据迁移
-- 契约：ershou-cloud/docs/full-split-contract.md 第四节（ershou_map 归属 trade_point）
--
-- 幂等说明：
--   * 建库/建表均 IF NOT EXISTS；
--   * 迁移段 **TRUNCATE + INSERT ... SELECT** = 每次执行都重置为旧库 `ershou.trade_point` 快照
--     （与 migrate-data.ps1 的“快照迁移”口径一致）。**正式切流后不要再执行**，否则新库新增点位会丢；
--   * 账号已由 Lead 预建（ershou_map@'%'），这里保留 IF NOT EXISTS + GRANT 兜底。
--
-- 执行：
--   powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\ershou-cloud\sql\split-map.sql
-- =============================================================

-- 1) 建库（独立库，不跨库 SQL）
CREATE DATABASE IF NOT EXISTS `ershou_map`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 2) 建表：结构完全复制旧库 trade_point（保留 merge_group 等历史列，保证 JSON/字段兼容）
CREATE TABLE IF NOT EXISTS `ershou_map`.`trade_point` LIKE `ershou`.`trade_point`;

-- 3) 数据迁移（快照；TRUNCATE 需 root 执行）
TRUNCATE TABLE `ershou_map`.`trade_point`;
INSERT INTO `ershou_map`.`trade_point` SELECT * FROM `ershou`.`trade_point`;

-- 4) 服务账号兜底（Lead 已建则跳过；密码与其它服务统一）
CREATE USER IF NOT EXISTS 'ershou_map'@'%' IDENTIFIED BY '__MYSQL_PASSWORD__';
GRANT ALL PRIVILEGES ON `ershou_map`.* TO 'ershou_map'@'%';
FLUSH PRIVILEGES;

-- 5) 校验（期望：旧库 21 行 → 新库 21 行；campus_id 分布一致）
SELECT '旧库 ershou.trade_point 行数' AS item, COUNT(*) AS cnt FROM `ershou`.`trade_point`
UNION ALL
SELECT '新库 ershou_map.trade_point 行数', COUNT(*) FROM `ershou_map`.`trade_point`
UNION ALL
SELECT '新库 按校区', COUNT(*) FROM `ershou_map`.`trade_point` WHERE campus_id = 2;

SELECT campus_id, COUNT(*) AS cnt FROM `ershou_map`.`trade_point` GROUP BY campus_id;
