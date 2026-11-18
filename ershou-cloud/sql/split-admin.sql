-- =============================================================
-- 全量拆分 T107：运营后台独立库 ershou_admin
-- 归属表：sys_audit_log（操作审计）/ report_ticket（举报工单）/ ai_audit_record（AI 审核记录）
-- 说明：
--   1) 数据从旧库 ershou 复制（CREATE TABLE ... LIKE + INSERT IGNORE SELECT，可重复执行，
--      重复执行不会覆盖新服务已经写入的审计/工单数据）；
--   2) 归属约定（full-split-contract 第六节）：ai_audit_record 归本库，item-service 不建/不写；
--      item_review 归 ershou_item，不在本库；
--   3) 本脚本只在切换前执行一次；旧库不再写入这些表。
-- 执行：powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\ershou-cloud\sql\split-admin.sql
-- =============================================================

-- ---------- 1. 库与账号 ----------
CREATE DATABASE IF NOT EXISTS `ershou_admin`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'ershou_admin'@'%' IDENTIFIED BY '__MYSQL_PASSWORD__';
CREATE USER IF NOT EXISTS 'ershou_admin'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
-- 幂等校准密码（账号可能是 lead 预建的）
ALTER USER 'ershou_admin'@'%' IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_admin'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
GRANT ALL PRIVILEGES ON `ershou_admin`.* TO 'ershou_admin'@'%';
GRANT ALL PRIVILEGES ON `ershou_admin`.* TO 'ershou_admin'@'localhost';
FLUSH PRIVILEGES;

-- ---------- 2. 建表（结构完全复制旧库，保持与单体一致） ----------
CREATE TABLE IF NOT EXISTS `ershou_admin`.`sys_audit_log` LIKE `ershou`.`sys_audit_log`;
CREATE TABLE IF NOT EXISTS `ershou_admin`.`report_ticket` LIKE `ershou`.`report_ticket`;
CREATE TABLE IF NOT EXISTS `ershou_admin`.`ai_audit_record` LIKE `ershou`.`ai_audit_record`;

-- ---------- 3. 数据迁移（保留原 id，审计连续性） ----------
-- sys_audit_log：旧库现有约 893 行
INSERT IGNORE INTO `ershou_admin`.`sys_audit_log` SELECT * FROM `ershou`.`sys_audit_log`;
-- report_ticket：旧库 0 行（功能此前未实现，本服务新接口使用新表）
INSERT IGNORE INTO `ershou_admin`.`report_ticket` SELECT * FROM `ershou`.`report_ticket`;
-- ai_audit_record：旧库 0 行（机审结果此前未落库，本服务只读展示）
INSERT IGNORE INTO `ershou_admin`.`ai_audit_record` SELECT * FROM `ershou`.`ai_audit_record`;

-- ---------- 4. 校验输出 ----------
SELECT 'ershou_admin.sys_audit_log' AS tbl, COUNT(*) AS rows_cnt FROM `ershou_admin`.`sys_audit_log`
UNION ALL SELECT 'ershou_admin.report_ticket', COUNT(*) FROM `ershou_admin`.`report_ticket`
UNION ALL SELECT 'ershou_admin.ai_audit_record', COUNT(*) FROM `ershou_admin`.`ai_audit_record`;
