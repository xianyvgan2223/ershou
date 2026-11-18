-- =============================================================
-- T102 全量拆分 · item-service 数据迁移脚本（幂等，可重复执行）
-- =============================================================
-- 契约：ershou-cloud/docs/full-split-contract.md §二/§四
-- 数据归属：ershou_item = item / item_category / item_review
--   - ai_audit_record 归 ershou_admin（admin 库，item-service 不建/不写，本脚本不处理）
--
-- 幂等策略：先 TRUNCATE 目标表再 INSERT ... SELECT（每次执行 = 旧库当前快照的完整拷贝）；
--   item 新库比旧库多一列 sale_count（RabbitMQ 异步累加），复制时显式置 0。
--
-- 执行方式（root，与契约一致）：
--   & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --host=127.0.0.1 --port=3306 --user=root --password=*** --default-character-set=utf8mb4 < ershou-cloud/sql/split-item.sql
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 0. 前置：确保 item / item_category 表存在（沿用 init-cloud-databases.sql 的云表结构；
--    若库是新建的，item_category 用旧库同构建表兜底）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ershou_item`.`item_category` LIKE `ershou`.`item_category`;

-- item_review：旧库同构建表（item-service 读写审核队列；admin-service 经 inner API 访问）
CREATE TABLE IF NOT EXISTS `ershou_item`.`item_review` LIKE `ershou`.`item_review`;

-- -------------------------------------------------------------
-- 1. item_category：10 行（旧库 10 行）
-- -------------------------------------------------------------
TRUNCATE TABLE `ershou_item`.`item_category`;
INSERT INTO `ershou_item`.`item_category`
  (`id`, `parent_id`, `name`, `icon`, `sort`, `school_id`, `status`)
SELECT
  `id`, `parent_id`, `name`, `icon`, `sort`, `school_id`, `status`
FROM `ershou`.`item_category`;

-- -------------------------------------------------------------
-- 2. item：17 行；新库多 sale_count 列，复制时置 0
--    （显式列清单：新旧库列顺序不同，禁止 INSERT SELECT *）
-- -------------------------------------------------------------
TRUNCATE TABLE `ershou_item`.`item`;
INSERT INTO `ershou_item`.`item`
  (`id`, `seller_id`, `seller_type`, `platform_point_id`, `school_id`, `campus_id`,
   `title`, `description`, `category_id`, `condition_level`, `price`, `original_price`,
   `negotiable`, `stock`, `is_unique`, `cover_url`, `images`, `video_url`, `trade_mode`,
   `prefer_point_id`, `status`, `audit_result`, `audit_reason`, `reject_code`,
   `lock_user_id`, `lock_expire_at`, `expire_at`, `published_at`, `view_count`,
   `chat_count`, `fav_count`, `version`, `sale_count`, `created_at`, `updated_at`,
   `last_live_title`, `last_live_desc_hash`)
SELECT
  `id`, `seller_id`, `seller_type`, `platform_point_id`, `school_id`, `campus_id`,
  `title`, `description`, `category_id`, `condition_level`, `price`, `original_price`,
  `negotiable`, `stock`, `is_unique`, `cover_url`, `images`, `video_url`, `trade_mode`,
  `prefer_point_id`, `status`, `audit_result`, `audit_reason`, `reject_code`,
  `lock_user_id`, `lock_expire_at`, `expire_at`, `published_at`, `view_count`,
  `chat_count`, `fav_count`, `version`, 0, `created_at`, `updated_at`,
  `last_live_title`, `last_live_desc_hash`
FROM `ershou`.`item`;

-- -------------------------------------------------------------
-- 3. item_review：审核队列（旧库 45 行；一件商品只留一条当前记录，uk_item 唯一索引）
-- -------------------------------------------------------------
TRUNCATE TABLE `ershou_item`.`item_review`;
INSERT INTO `ershou_item`.`item_review`
  (`id`, `item_id`, `seller_id`, `title`, `review_type`, `revision`, `status`,
   `review_action`, `queued_at`, `first_queued_at`, `content_changed_at`,
   `reviewer_id`, `claimed_at`, `reviewed_at`, `note`, `created_at`, `updated_at`)
SELECT
  `id`, `item_id`, `seller_id`, `title`, `review_type`, `revision`, `status`,
  `review_action`, `queued_at`, `first_queued_at`, `content_changed_at`,
  `reviewer_id`, `claimed_at`, `reviewed_at`, `note`, `created_at`, `updated_at`
FROM `ershou`.`item_review`;

-- -------------------------------------------------------------
-- 4. 执行后核对输出（旧库 vs 新库，期望两列数字一致）
-- -------------------------------------------------------------
SELECT 'item' AS tbl,
       (SELECT COUNT(*) FROM `ershou`.`item`) AS old_cnt,
       (SELECT COUNT(*) FROM `ershou_item`.`item`) AS new_cnt,
       (SELECT COUNT(*) FROM `ershou_item`.`item` WHERE `status` = 2) AS on_sale
UNION ALL
SELECT 'item_category',
       (SELECT COUNT(*) FROM `ershou`.`item_category`),
       (SELECT COUNT(*) FROM `ershou_item`.`item_category`),
       NULL
UNION ALL
SELECT 'item_review',
       (SELECT COUNT(*) FROM `ershou`.`item_review`),
       (SELECT COUNT(*) FROM `ershou_item`.`item_review`),
       NULL;
