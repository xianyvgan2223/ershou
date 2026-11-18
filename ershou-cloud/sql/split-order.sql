-- =============================================================
-- T103 · order-service 全量拆分 · ershou_order 库表与数据迁移
-- 作者：order-member（交易域）
-- 时间：2026-11-13
-- 说明：
--   1) 本脚本把单体 `ershou` 库中归属交易域的表结构 + 数据同步到 `ershou_order` 库：
--      cart_item / favorite / favorite_folder / meetup_order / credit_log / notification / review
--   2) 幂等可重复执行：CREATE TABLE IF NOT EXISTS ... LIKE + REPLACE INTO ... SELECT（按主键覆盖/补齐）
--   3) 表结构以旧单体 DDL 为准（CREATE TABLE ... LIKE 连索引一起复制），不做字段裁剪
--   4) 执行账号：root（本机 127.0.0.1:3306），迁移后旧 ershou 库停写（由 Lead 统一切换）
--   5) 本脚本**只操作 ershou_order 目标库**；对源库 ershou 只读
--   6) undo_log 已由 init-cloud-databases.sql 建好，这里不重复创建
-- =============================================================

-- ---------- 0. 目标库 ----------
CREATE DATABASE IF NOT EXISTS `ershou_order` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `ershou_order`;

-- ---------- 1. 购物车 ----------
CREATE TABLE IF NOT EXISTS `cart_item` LIKE `ershou`.`cart_item`;

-- ---------- 2. 收藏 / 收藏夹 ----------
CREATE TABLE IF NOT EXISTS `favorite` LIKE `ershou`.`favorite`;
CREATE TABLE IF NOT EXISTS `favorite_folder` LIKE `ershou`.`favorite_folder`;

-- ---------- 3. 面交单（此前已有 meetup_order，这里保证结构一致）----------
CREATE TABLE IF NOT EXISTS `meetup_order` LIKE `ershou`.`meetup_order`;

-- ---------- 4. 信用分流水 ----------
CREATE TABLE IF NOT EXISTS `credit_log` LIKE `ershou`.`credit_log`;

-- ---------- 5. 通知 / 双向评价（域内预留表，与旧单体结构一致）----------
CREATE TABLE IF NOT EXISTS `notification` LIKE `ershou`.`notification`;
CREATE TABLE IF NOT EXISTS `review` LIKE `ershou`.`review`;

-- =============================================================
-- 数据迁移（源库只读；REPLACE 按主键覆盖/补齐，重复执行结果一致）
-- =============================================================
REPLACE INTO `cart_item`        SELECT * FROM `ershou`.`cart_item`;
REPLACE INTO `favorite`         SELECT * FROM `ershou`.`favorite`;
REPLACE INTO `favorite_folder`  SELECT * FROM `ershou`.`favorite_folder`;
REPLACE INTO `meetup_order`     SELECT * FROM `ershou`.`meetup_order`;
REPLACE INTO `credit_log`       SELECT * FROM `ershou`.`credit_log`;
REPLACE INTO `notification`     SELECT * FROM `ershou`.`notification`;
REPLACE INTO `review`           SELECT * FROM `ershou`.`review`;

-- 自增起点修正（避免新写入主键与旧数据冲突）：空表也执行，取当前最大 id+1
ALTER TABLE `cart_item`       AUTO_INCREMENT = 1;
ALTER TABLE `favorite`        AUTO_INCREMENT = 1;
ALTER TABLE `favorite_folder` AUTO_INCREMENT = 1;
ALTER TABLE `credit_log`      AUTO_INCREMENT = 1;
ALTER TABLE `notification`    AUTO_INCREMENT = 1;
ALTER TABLE `review`          AUTO_INCREMENT = 1;

-- ---------- 校验输出（执行后应看到 7 张业务表 + undo_log）----------
SELECT 'ershou_order' AS db, TABLE_NAME, TABLE_ROWS
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'ershou_order'
 ORDER BY TABLE_NAME;

SELECT 'cart_item' AS tbl, COUNT(*) AS rows_now FROM cart_item
UNION ALL SELECT 'favorite', COUNT(*) FROM favorite
UNION ALL SELECT 'favorite_folder', COUNT(*) FROM favorite_folder
UNION ALL SELECT 'meetup_order', COUNT(*) FROM meetup_order
UNION ALL SELECT 'credit_log', COUNT(*) FROM credit_log
UNION ALL SELECT 'notification', COUNT(*) FROM notification
UNION ALL SELECT 'review', COUNT(*) FROM review;
