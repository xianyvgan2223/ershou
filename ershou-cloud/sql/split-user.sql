-- =============================================================
-- T101 全量拆分 · user-service 数据库脚本（ershou_user）
-- 库：ershou_user（MySQL 127.0.0.1:3306，root）
-- 内容：
--   1) 建 user_block（旧库同构；user_block 属 ershou_user，见契约第四节数据归属）
--   2) 从旧库 ershou 复制 school / campus / user_block（先清后插，保留原 id）
--   3) sys_user 缺口兜底：把旧库有、新库没有的用户补进来（INSERT IGNORE，不覆盖新库已有数据）
--   4) 校验输出：各表新旧行数对比
--
-- 幂等：可重复执行；只覆盖"静态字典 + 拉黑关系"，绝不 UPDATE 新库已有 sys_user 行
--       （8081 启动后新注册/改资料的用户数据不会被本脚本冲掉）。
--
-- 执行方式（root）：
--   mysql --host=127.0.0.1 --user=root --password=*** --default-character-set=utf8mb4 < ershou-cloud/sql/split-user.sql
-- =============================================================

-- -------------------------------------------------------------
-- 1. user_block（与旧库 ershou.user_block 完全同构）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ershou_user`.`user_block` (
  `id`              BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT   NOT NULL COMMENT '发起拉黑的人',
  `blocked_user_id` BIGINT   NOT NULL COMMENT '被拉黑的人',
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_pair` (`user_id`, `blocked_user_id`),
  KEY `idx_block_target` (`blocked_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户拉黑';

-- -------------------------------------------------------------
-- 2. 学校 / 校区：全量复制（静态字典，行数极小；先清后插保证与旧库一致）
-- -------------------------------------------------------------
START TRANSACTION;

DELETE FROM `ershou_user`.`school`;
INSERT INTO `ershou_user`.`school` (`id`, `name`, `city`, `source`, `status`, `created_at`, `updated_at`)
SELECT `id`, `name`, `city`, `source`, `status`, `created_at`, `updated_at`
FROM `ershou`.`school`;

DELETE FROM `ershou_user`.`campus`;
INSERT INTO `ershou_user`.`campus` (`id`, `school_id`, `name`, `map_type`, `map_config`, `status`, `created_at`)
SELECT `id`, `school_id`, `name`, `map_type`, `map_config`, `status`, `created_at`
FROM `ershou`.`campus`;

-- 拉黑关系：先清后插（旧库当前 0 行也照常执行；保留 id 便于对账）
DELETE FROM `ershou_user`.`user_block`;
INSERT INTO `ershou_user`.`user_block` (`id`, `user_id`, `blocked_user_id`, `created_at`)
SELECT `id`, `user_id`, `blocked_user_id`, `created_at`
FROM `ershou`.`user_block`;

COMMIT;

-- -------------------------------------------------------------
-- 3. sys_user 缺口兜底（可选但建议）：
--    迁移快照之后旧单体若还注册过新用户，这里把"旧库有、新库没有"的 id 补进来。
--    INSERT IGNORE：id 或 phone_hash 冲突的行跳过，绝不覆盖新库已存在的数据。
-- -------------------------------------------------------------
INSERT IGNORE INTO `ershou_user`.`sys_user`
SELECT * FROM `ershou`.`sys_user`;

-- -------------------------------------------------------------
-- 4. 校验（结果对照：u_* = 新库 ershou_user，o_* = 旧库 ershou）
-- -------------------------------------------------------------
SELECT 'ershou_user tables' AS section;
SHOW TABLES FROM `ershou_user`;

SELECT 'row counts (new vs old)' AS section,
       (SELECT COUNT(*) FROM `ershou_user`.`sys_user`)   AS u_sys_user,
       (SELECT COUNT(*) FROM `ershou`.`sys_user`)        AS o_sys_user,
       (SELECT COUNT(*) FROM `ershou_user`.`school`)     AS u_school,
       (SELECT COUNT(*) FROM `ershou`.`school`)          AS o_school,
       (SELECT COUNT(*) FROM `ershou_user`.`campus`)     AS u_campus,
       (SELECT COUNT(*) FROM `ershou`.`campus`)          AS o_campus,
       (SELECT COUNT(*) FROM `ershou_user`.`user_block`) AS u_user_block,
       (SELECT COUNT(*) FROM `ershou`.`user_block`)      AS o_user_block;

SELECT 'school data' AS section;
SELECT id, name, city, source, status FROM `ershou_user`.`school` ORDER BY id;

SELECT 'campus data' AS section;
SELECT id, school_id, name, map_type, status FROM `ershou_user`.`campus` ORDER BY id;
