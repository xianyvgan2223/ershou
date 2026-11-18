-- =============================================================
-- 第 8 轮全量拆分 · T106 平台服务（ershou-platform-service, :8086）
--
-- 数据归属：platform_* 与 consign_order 从旧库 ershou 迁到 ershou_platform。
-- 迁移方式（契约第 4 节）：CREATE TABLE LIKE + INSERT SELECT；可重复执行。
-- 说明：
--   * item 表（平台商品）不迁入本库 —— 归 item-service（ershou_item），平台服务通过 Feign 读写；
--   * 本脚本不跨库写业务，只做一次性数据迁移（完成后旧库 ershou 停写）；
--   * platform_* 表与旧库结构完全一致（含唯一键/索引），后续实体字段无需调整。
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p < split-platform.sql
-- =============================================================

-- 1. 建库 + 独立账号（密码与 .env 一致：__REDACTED_DB_PASSWORD__）
CREATE DATABASE IF NOT EXISTS `ershou_platform`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'ershou_platform'@'%' IDENTIFIED BY '__REDACTED_DB_PASSWORD__';
CREATE USER IF NOT EXISTS 'ershou_platform'@'localhost' IDENTIFIED BY '__REDACTED_DB_PASSWORD__';
ALTER USER 'ershou_platform'@'%' IDENTIFIED BY '__REDACTED_DB_PASSWORD__';
ALTER USER 'ershou_platform'@'localhost' IDENTIFIED BY '__REDACTED_DB_PASSWORD__';
GRANT ALL PRIVILEGES ON `ershou_platform`.* TO 'ershou_platform'@'%';
GRANT ALL PRIVILEGES ON `ershou_platform`.* TO 'ershou_platform'@'localhost';
FLUSH PRIVILEGES;

-- 2. 建表（结构与旧库一致；IF NOT EXISTS 幂等）
CREATE TABLE IF NOT EXISTS `ershou_platform`.`platform_consign_point`
  LIKE `ershou`.`platform_consign_point`;
CREATE TABLE IF NOT EXISTS `ershou_platform`.`platform_coupon`
  LIKE `ershou`.`platform_coupon`;
CREATE TABLE IF NOT EXISTS `ershou_platform`.`platform_coupon_user`
  LIKE `ershou`.`platform_coupon_user`;
CREATE TABLE IF NOT EXISTS `ershou_platform`.`platform_flash_sale`
  LIKE `ershou`.`platform_flash_sale`;
CREATE TABLE IF NOT EXISTS `ershou_platform`.`platform_flash_sale_item`
  LIKE `ershou`.`platform_flash_sale_item`;
CREATE TABLE IF NOT EXISTS `ershou_platform`.`platform_seckill_record`
  LIKE `ershou`.`platform_seckill_record`;
CREATE TABLE IF NOT EXISTS `ershou_platform`.`consign_order`
  LIKE `ershou`.`consign_order`;

-- 3. 数据迁移（可重复执行：先清空目标表再整表复制）
DELETE FROM `ershou_platform`.`platform_coupon_user`;
DELETE FROM `ershou_platform`.`platform_seckill_record`;
DELETE FROM `ershou_platform`.`platform_flash_sale_item`;
DELETE FROM `ershou_platform`.`platform_flash_sale`;
DELETE FROM `ershou_platform`.`platform_coupon`;
DELETE FROM `ershou_platform`.`platform_consign_point`;
DELETE FROM `ershou_platform`.`consign_order`;

INSERT INTO `ershou_platform`.`platform_consign_point`
  SELECT * FROM `ershou`.`platform_consign_point`;
INSERT INTO `ershou_platform`.`platform_coupon`
  SELECT * FROM `ershou`.`platform_coupon`;
INSERT INTO `ershou_platform`.`platform_coupon_user`
  SELECT * FROM `ershou`.`platform_coupon_user`;
INSERT INTO `ershou_platform`.`platform_flash_sale`
  SELECT * FROM `ershou`.`platform_flash_sale`;
INSERT INTO `ershou_platform`.`platform_flash_sale_item`
  SELECT * FROM `ershou`.`platform_flash_sale_item`;
INSERT INTO `ershou_platform`.`platform_seckill_record`
  SELECT * FROM `ershou`.`platform_seckill_record`;
INSERT INTO `ershou_platform`.`consign_order`
  SELECT * FROM `ershou`.`consign_order`;

-- 3.1 演示数据时间窗归一（幂等；仅演示环境）
-- 旧库平台种子数据写于 2026-10-16/17，活动时间窗已过期，迁移后平台页会显示"暂无券/无秒杀"。
-- 这里把启用中的券/进行中的场次调整到 当前时刻前 1 天 ~ 后 7 天，保证演示可用；
-- 生产环境请删掉本段（保留旧库原始时间）。
UPDATE `ershou_platform`.`platform_coupon`
   SET start_at = DATE_SUB(NOW(), INTERVAL 1 DAY),
       end_at   = DATE_ADD(NOW(), INTERVAL 7 DAY)
 WHERE status = 1;
UPDATE `ershou_platform`.`platform_flash_sale`
   SET start_at = DATE_SUB(NOW(), INTERVAL 10 MINUTE),
       end_at   = DATE_ADD(NOW(), INTERVAL 2 DAY)
 WHERE status IN (1, 2);
-- 4. 结果自检（执行后应看到：点位 4 / 券 3 / 领券记录 2 / 场次 1 / 场次商品 2；consign_order 旧库为空）
-- SELECT 'platform_consign_point' AS tbl, COUNT(*) FROM `ershou_platform`.`platform_consign_point`
-- UNION ALL SELECT 'platform_coupon', COUNT(*) FROM `ershou_platform`.`platform_coupon`
-- UNION ALL SELECT 'platform_coupon_user', COUNT(*) FROM `ershou_platform`.`platform_coupon_user`
-- UNION ALL SELECT 'platform_flash_sale', COUNT(*) FROM `ershou_platform`.`platform_flash_sale`
-- UNION ALL SELECT 'platform_flash_sale_item', COUNT(*) FROM `ershou_platform`.`platform_flash_sale_item`
-- UNION ALL SELECT 'platform_seckill_record', COUNT(*) FROM `ershou_platform`.`platform_seckill_record`
-- UNION ALL SELECT 'consign_order', COUNT(*) FROM `ershou_platform`.`consign_order`;
