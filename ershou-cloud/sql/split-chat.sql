-- =============================================================
-- T104 · chat-service 全量拆分 · ershou_chat 库表与数据迁移
-- 作者：chat-member（沟通域）
-- 时间：2026-11-13
-- 说明：
--   1) 本脚本把单体 `ershou` 库中归属沟通域的表结构 + 数据同步到 `ershou_chat` 库：
--      chat_session / chat_message
--   2) 幂等可重复执行：CREATE TABLE IF NOT EXISTS ... LIKE + REPLACE INTO ... SELECT（按主键覆盖/补齐）
--   3) 表结构以旧单体 DDL 为准（CREATE TABLE ... LIKE 连索引一起复制），不做字段裁剪
--   4) 执行账号：root（本机 127.0.0.1:3306），迁移后旧 ershou 库停写（由 Lead 统一切换）
--   5) 本脚本**只操作 ershou_chat 目标库**；对源库 ershou 只读
--   6) 不建 undo_log（chat-service 不使用 Seata 全局事务）
-- =============================================================

-- ---------- 0. 目标库 ----------
CREATE DATABASE IF NOT EXISTS `ershou_chat` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `ershou_chat`;

-- ---------- 1. 会话 / 消息（表结构直接复制旧库，含唯一索引与普通索引）----------
CREATE TABLE IF NOT EXISTS `chat_session` LIKE `ershou`.`chat_session`;
CREATE TABLE IF NOT EXISTS `chat_message` LIKE `ershou`.`chat_message`;

-- ---------- 2. 数据迁移（源库只读；REPLACE 按主键覆盖/补齐，重复执行结果一致）----------
REPLACE INTO `chat_session` SELECT * FROM `ershou`.`chat_session`;
REPLACE INTO `chat_message` SELECT * FROM `ershou`.`chat_message`;

-- ---------- 3. 自增起点修正（REPLACE 后 MySQL 会自动取 max(id)+1；这里显式兜底空表场景）----------
ALTER TABLE `chat_session` AUTO_INCREMENT = 1;
ALTER TABLE `chat_message` AUTO_INCREMENT = 1;

-- ---------- 4. 独立库账号（与其它拆分服务同口径：% + localhost，密码同 .env）----------
CREATE USER IF NOT EXISTS 'ershou_chat'@'%' IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_chat'@'%' IDENTIFIED BY '__MYSQL_PASSWORD__';
GRANT ALL PRIVILEGES ON `ershou_chat`.* TO 'ershou_chat'@'%';

CREATE USER IF NOT EXISTS 'ershou_chat'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_chat'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
GRANT ALL PRIVILEGES ON `ershou_chat`.* TO 'ershou_chat'@'localhost';

FLUSH PRIVILEGES;

-- ---------- 5. 校验输出（执行后应看到 2 张业务表 + 行数）----------
SELECT 'ershou_chat' AS db, TABLE_NAME, TABLE_ROWS
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'ershou_chat'
 ORDER BY TABLE_NAME;

SELECT 'chat_session' AS tbl, COUNT(*) AS rows_now FROM chat_session
UNION ALL SELECT 'chat_message', COUNT(*) FROM chat_message;

-- 迁移一致性对照（旧库只读；两行应相等）
SELECT 'old.chat_session' AS src, COUNT(*) AS rows_now FROM `ershou`.`chat_session`
UNION ALL SELECT 'old.chat_message', COUNT(*) FROM `ershou`.`chat_message`;
