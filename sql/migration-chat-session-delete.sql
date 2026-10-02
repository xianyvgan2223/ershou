-- =============================================================
-- 私聊会话「删除聊天记录」：每用户软删除
-- =============================================================
-- 需求：聊天页面能删除会话（聊天记录）
--
-- 为什么是"每用户软删除"而不是物理删除整行：
--   一条 chat_session 是**两个人共用**的（user_a / user_b）。
--   我删掉它如果直接 DELETE 整行，对方的会话、对方的历史消息入口会一起消失 ——
--   这是别人的数据，不能被我的一次点击删掉。
--   所以按人记录"这个人把它从列表里删掉了"：
--     deleted_a / deleted_b  = 1 表示该侧已删除
--     deleted_a_at / _b_at   = 删除时间（留痕，也方便以后做"清空已删除"的清理任务）
--
-- 与「对方再发消息自动恢复」的配合：
--   删除只是从**我的列表**里移除。对方若又发来消息，发送路径会把我这侧的
--   deleted_* 置回 0（否则消息落库了、我却永远看不到这个会话，等于丢消息）。
--   这与微信一致：删了聊天，对方再发消息，会话重新出现。
--
-- 为什么不复用已有的 chat_session.status：
--   那一列已经被 ChatService#toSessionVO 用来控制 canSend（status=1 才允许发送），
--   抢过来做"已删除"会让语义打架（删掉的会话到底能不能发消息？）。
--   新增列只花两个 TINYINT，语义清晰、互不干扰。
--
-- 对存量数据的影响：新列默认 0（都未删除），所以升级后所有人的会话列表
-- 与未读数**完全不变**，不需要回填。
-- =============================================================

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'deleted_a') = 0,
  'ALTER TABLE chat_session
     ADD COLUMN deleted_a    TINYINT  NOT NULL DEFAULT 0 COMMENT ''user_a 是否已从自己列表删除该会话'',
     ADD COLUMN deleted_b    TINYINT  NOT NULL DEFAULT 0 COMMENT ''user_b 是否已从自己列表删除该会话'',
     ADD COLUMN deleted_a_at DATETIME DEFAULT NULL COMMENT ''user_a 删除时间'',
     ADD COLUMN deleted_b_at DATETIME DEFAULT NULL COMMENT ''user_b 删除时间''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 列表查询按 (user_a, user_b) 过滤后再排 deleted，加个组合索引让"我的未删除会话"走索引
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_user_a_del') = 0,
  'ALTER TABLE chat_session ADD KEY idx_session_user_a_del (user_a, deleted_a)',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_user_b_del') = 0,
  'ALTER TABLE chat_session ADD KEY idx_session_user_b_del (user_b, deleted_b)',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
