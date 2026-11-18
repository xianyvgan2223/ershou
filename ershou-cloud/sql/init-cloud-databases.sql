-- =============================================================
-- 第 5 轮 · Spring Cloud 重构：分库建库 + 独立账号 + 建表（幂等可重复执行）
-- 对应契约：智能体团队/第5轮-SpringCloud重构-设计.md §2.1 / §三 / §五
-- =============================================================
-- 目标（同一 MySQL 实例、独立 schema + 独立账号；改 URL 即可物理拆分）：
--   ershou_user  账号 ershou_user  ：sys_user / school / campus
--   ershou_item  账号 ershou_item  ：item（新增 sale_count）/ item_category / undo_log(Seata AT)
--   ershou_order 账号 ershou_order ：meetup_order / undo_log(Seata AT)
-- 旧库 ershou 保持不动（本轮只做一次性快照迁移，见 migrate-data.ps1）。
--
-- 执行方式（root，幂等，可反复跑；密码从 .env 读取，仓库里不落明文）：
--   powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\ershou-cloud\sql\init-cloud-databases.sql
--
-- 说明：
--   * 脚本里的账号密码用 __MYSQL_PASSWORD__ 占位，由 scripts/run-sql.ps1 从 .env 替换。
--   * 表结构 = 单体 ershou 库 SHOW CREATE TABLE 的结果，按云服务用途裁剪：
--       - item 去掉 FULLTEXT(ngram) 索引（item-service 本轮不做全文检索，避免依赖 ngram 插件）
--       - item 新增 sale_count INT NOT NULL DEFAULT 0（RabbitMQ 异步累加演示用）
--       - 去掉 AUTO_INCREMENT 起始值（新库从头开始，迁移会显式插入 id）
--   * undo_log 是 Seata 1.8.0 AT 模式标准表，item/order 两库都必须有。
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 1. 建库（幂等）
-- -------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `ershou_user`  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `ershou_item`  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `ershou_order` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- -------------------------------------------------------------
-- 2. 独立账号 + 授权（幂等；host 同时覆盖 % 与 localhost）
--    注意：CREATE USER 不带明文回显；ALTER USER 保证重复执行时密码也一致
-- -------------------------------------------------------------
CREATE USER IF NOT EXISTS 'ershou_user'@'%'         IDENTIFIED BY '__MYSQL_PASSWORD__';
CREATE USER IF NOT EXISTS 'ershou_user'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
CREATE USER IF NOT EXISTS 'ershou_item'@'%'         IDENTIFIED BY '__MYSQL_PASSWORD__';
CREATE USER IF NOT EXISTS 'ershou_item'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
CREATE USER IF NOT EXISTS 'ershou_order'@'%'        IDENTIFIED BY '__MYSQL_PASSWORD__';
CREATE USER IF NOT EXISTS 'ershou_order'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_user'@'%'         IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_user'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_item'@'%'         IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_item'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_order'@'%'        IDENTIFIED BY '__MYSQL_PASSWORD__';
ALTER USER 'ershou_order'@'localhost' IDENTIFIED BY '__MYSQL_PASSWORD__';
GRANT ALL PRIVILEGES ON `ershou_user`.*  TO 'ershou_user'@'%';
GRANT ALL PRIVILEGES ON `ershou_user`.*  TO 'ershou_user'@'localhost';
GRANT ALL PRIVILEGES ON `ershou_item`.*  TO 'ershou_item'@'%';
GRANT ALL PRIVILEGES ON `ershou_item`.*  TO 'ershou_item'@'localhost';
GRANT ALL PRIVILEGES ON `ershou_order`.* TO 'ershou_order'@'%';
GRANT ALL PRIVILEGES ON `ershou_order`.* TO 'ershou_order'@'localhost';
FLUSH PRIVILEGES;

-- =============================================================
-- 3. ershou_user：sys_user / school / campus
-- =============================================================
USE `ershou_user`;

CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phone` varchar(64) NOT NULL COMMENT '手机号(AES加密存储)',
  `phone_hash` char(64) NOT NULL COMMENT '手机号SHA256(用于唯一索引与查询)',
  `nickname` varchar(32) NOT NULL COMMENT '昵称',
  `avatar` varchar(255) DEFAULT NULL,
  `school_id` bigint DEFAULT NULL,
  `campus_id` bigint DEFAULT NULL,
  `role_flags` int NOT NULL DEFAULT '0' COMMENT '位标记 1=普通 2=多库存商家 4=代卖员 8=运营',
  `identity_flag` tinyint NOT NULL DEFAULT '0' COMMENT '身份徽章 0=普通 1=商家 2=代卖员 4=校内邮箱已验证(可选)',
  `shop_name` varchar(64) DEFAULT NULL COMMENT '商家商铺名',
  `shop_intro` varchar(255) DEFAULT NULL COMMENT '商家售后说明',
  `license_verified` tinyint NOT NULL DEFAULT '0' COMMENT '营业执照徽章(可选) 0/1',
  `credit_score` int NOT NULL DEFAULT '100' COMMENT '信用分',
  `credit_level` tinyint NOT NULL DEFAULT '2' COMMENT '1=优秀 2=良好 3=一般 4=风险',
  `risk_score` int NOT NULL DEFAULT '0',
  `risk_tags` json DEFAULT NULL,
  `device_id` varchar(64) DEFAULT NULL COMMENT '设备指纹',
  `device_account_count` int NOT NULL DEFAULT '1' COMMENT '同设备账号数',
  `publish_limit` int NOT NULL DEFAULT '5' COMMENT '每日发布上限(新号限额)',
  `chat_limit` int NOT NULL DEFAULT '20' COMMENT '每日私聊上限',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=正常 2=限权 3=封禁',
  `ban_until` datetime DEFAULT NULL,
  `last_login_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone_hash` (`phone_hash`),
  KEY `idx_user_campus` (`campus_id`),
  KEY `idx_user_credit` (`credit_score`),
  KEY `idx_user_device` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户(轻注册)';

CREATE TABLE IF NOT EXISTS `school` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '学校名称',
  `city` varchar(32) DEFAULT NULL COMMENT '城市',
  `source` tinyint NOT NULL DEFAULT '1' COMMENT '来源 1=平台开通 2=用户申请',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用 0=停用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_school_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='学校';

CREATE TABLE IF NOT EXISTS `campus` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `school_id` bigint NOT NULL COMMENT '学校ID',
  `name` varchar(64) NOT NULL COMMENT '校区名称',
  `map_type` tinyint NOT NULL DEFAULT '1' COMMENT '1=自绘平面图 2=第三方地图(高德/腾讯)',
  `map_config` json DEFAULT NULL COMMENT '底图配置: {imgUrl,width,height,minZoom,maxZoom,center,polygon}',
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_campus_school` (`school_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='校区';

-- =============================================================
-- 4. ershou_item：item（+sale_count）/ item_category / undo_log
-- =============================================================
USE `ershou_item`;

CREATE TABLE IF NOT EXISTS `item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `seller_id` bigint NOT NULL,
  `seller_type` tinyint NOT NULL DEFAULT '1' COMMENT '卖家类型 1=个人卖家 2=平台自营(官方代卖)',
  `platform_point_id` bigint DEFAULT NULL COMMENT '平台代卖/自提点 id(platform_consign_point.id)，个人商品为 NULL',
  `school_id` bigint DEFAULT NULL,
  `campus_id` bigint DEFAULT NULL,
  `title` varchar(80) NOT NULL,
  `description` text,
  `category_id` bigint NOT NULL,
  `condition_level` tinyint NOT NULL DEFAULT '3' COMMENT '1=全新 2=几乎全新 3=轻微使用 4=明显使用 5=有瑕疵',
  `price` decimal(10,2) NOT NULL,
  `original_price` decimal(10,2) DEFAULT NULL,
  `negotiable` tinyint NOT NULL DEFAULT '1' COMMENT '是否可议价',
  `stock` int NOT NULL DEFAULT '1',
  `is_unique` tinyint NOT NULL DEFAULT '1' COMMENT '孤品标记',
  `cover_url` varchar(255) DEFAULT NULL,
  `images` json DEFAULT NULL COMMENT '图片URL数组 1-9',
  `video_url` varchar(255) DEFAULT NULL,
  `trade_mode` tinyint NOT NULL DEFAULT '1' COMMENT '1=仅面交 2=面交+邮寄(预留)',
  `prefer_point_id` bigint DEFAULT NULL COMMENT '常用面交点',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0=草稿 1=审核中 2=在售 3=已锁定 4=已售出 5=已下架 6=审核驳回 7=已过期 8=违规下架',
  `audit_result` tinyint DEFAULT NULL COMMENT '1=通过 2=驳回',
  `audit_reason` varchar(255) DEFAULT NULL,
  `reject_code` varchar(32) DEFAULT NULL,
  `lock_user_id` bigint DEFAULT NULL COMMENT '当前锁库用户',
  `lock_expire_at` datetime DEFAULT NULL COMMENT '锁库过期时间(30分钟)',
  `expire_at` datetime DEFAULT NULL COMMENT '商品有效期截止(默认发布后 7 天)',
  `published_at` datetime DEFAULT NULL,
  `view_count` int NOT NULL DEFAULT '0',
  `chat_count` int NOT NULL DEFAULT '0',
  `fav_count` int NOT NULL DEFAULT '0',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁',
  `sale_count` int NOT NULL DEFAULT '0' COMMENT '已售件数（第5轮新增：RabbitMQ order.created 异步累加）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `last_live_title` varchar(120) DEFAULT NULL COMMENT '上架时（审核员已看过）的标题快照',
  `last_live_desc_hash` char(32) DEFAULT NULL COMMENT '上架时（审核员已看过）的描述哈希（MD5）',
  PRIMARY KEY (`id`),
  KEY `idx_item_status_pub` (`status`,`published_at`),
  KEY `idx_item_seller` (`seller_id`,`status`),
  KEY `idx_item_campus_status` (`campus_id`,`status`,`published_at`),
  KEY `idx_item_category` (`category_id`,`status`),
  KEY `idx_item_lock` (`status`,`lock_expire_at`),
  KEY `idx_item_point` (`prefer_point_id`),
  KEY `idx_item_expire` (`status`,`expire_at`),
  KEY `idx_item_platform` (`seller_type`,`status`,`campus_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品（云 item-service）';

CREATE TABLE IF NOT EXISTS `item_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `name` varchar(32) NOT NULL,
  `icon` varchar(255) DEFAULT NULL,
  `sort` int NOT NULL DEFAULT '0',
  `school_id` bigint DEFAULT NULL COMMENT 'NULL=通用分类',
  `status` tinyint NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `idx_cat_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品分类';

-- 兼容性补丁（幂等）：若表已存在但缺本轮新增列/索引，这里补齐，保证脚本可重复执行
SET @has_sale := (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='ershou_item' AND TABLE_NAME='item' AND COLUMN_NAME='sale_count');
SET @sql := IF(@has_sale = 0,
  'ALTER TABLE ershou_item.item ADD COLUMN sale_count INT NOT NULL DEFAULT 0 COMMENT ''已售件数（第5轮新增：RabbitMQ order.created 异步累加）'' AFTER version',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA='ershou_item' AND TABLE_NAME='item' AND INDEX_NAME='idx_item_status_pub');
SET @sql := IF(@has_idx = 0,
  'ALTER TABLE ershou_item.item ADD KEY idx_item_status_pub (status, published_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Seata AT 模式必需表（1.8.0 标准 DDL）
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id`     bigint       NOT NULL COMMENT 'branch transaction id',
  `xid`           varchar(128) NOT NULL COMMENT 'global transaction id',
  `context`       varchar(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` longblob     NOT NULL COMMENT 'rollback info',
  `log_status`    int          NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created`   datetime(6)  NOT NULL COMMENT 'create datetime',
  `log_modified`  datetime(6)  NOT NULL COMMENT 'modify datetime',
  PRIMARY KEY (`branch_id`),
  UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AT transaction mode undo table';

-- =============================================================
-- 5. ershou_order：meetup_order / undo_log
-- =============================================================
USE `ershou_order`;

CREATE TABLE IF NOT EXISTS `meetup_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL COMMENT '业务单号',
  `batch_no` varchar(32) DEFAULT NULL COMMENT '合并面交行程号(跨卖家共享)',
  `buyer_id` bigint NOT NULL,
  `seller_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `item_title` varchar(80) NOT NULL COMMENT '快照',
  `item_cover` varchar(255) DEFAULT NULL COMMENT '快照',
  `quantity` int NOT NULL DEFAULT '1',
  `deal_price` decimal(10,2) DEFAULT NULL COMMENT '实际成交价(可选填写)',
  `point_id` bigint DEFAULT NULL COMMENT '面交点(可空=待协商)',
  `point_snapshot` json DEFAULT NULL COMMENT '点位快照(名称/坐标/指引,防篡改)',
  `time_slot_start` datetime DEFAULT NULL COMMENT '时间窗开始(可空=待协商)',
  `time_slot_end` datetime DEFAULT NULL COMMENT '时间窗结束(可空=待协商)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=待卖家确认 2=待面交 3=已完成 4=已取消 5=已失效 6=爽约 7=纠纷中',
  `verify_code` char(6) DEFAULT NULL COMMENT '6位核销码',
  `verify_expire_at` datetime DEFAULT NULL,
  `verified_at` datetime DEFAULT NULL,
  `verified_by` bigint DEFAULT NULL COMMENT '核销发起人',
  `buyer_confirm_at` datetime DEFAULT NULL,
  `seller_confirm_at` datetime DEFAULT NULL,
  `reschedule_count` int NOT NULL DEFAULT '0',
  `cancel_by` bigint DEFAULT NULL,
  `cancel_reason` varchar(255) DEFAULT NULL,
  `noshow_user_id` bigint DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_order_buyer` (`buyer_id`,`status`,`created_at`),
  KEY `idx_order_seller` (`seller_id`,`status`,`created_at`),
  KEY `idx_order_item` (`item_id`),
  KEY `idx_order_batch` (`batch_no`),
  KEY `idx_order_slot` (`status`,`time_slot_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='面交单（云 order-service）';

CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id`     bigint       NOT NULL COMMENT 'branch transaction id',
  `xid`           varchar(128) NOT NULL COMMENT 'global transaction id',
  `context`       varchar(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` longblob     NOT NULL COMMENT 'rollback info',
  `log_status`    int          NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created`   datetime(6)  NOT NULL COMMENT 'create datetime',
  `log_modified`  datetime(6)  NOT NULL COMMENT 'modify datetime',
  PRIMARY KEY (`branch_id`),
  UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AT transaction mode undo table';

-- =============================================================
-- 6. 执行后核对输出（人工看结果）
-- =============================================================
SELECT 'ershou_user' AS db, TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='ershou_user' ORDER BY TABLE_NAME;
SELECT 'ershou_item' AS db, TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='ershou_item' ORDER BY TABLE_NAME;
SELECT 'ershou_order' AS db, TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='ershou_order' ORDER BY TABLE_NAME;
