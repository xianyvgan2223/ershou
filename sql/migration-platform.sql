-- =============================================================
-- 第 2 轮 · 平台代卖（官方严选）迁移脚本
-- =============================================================
-- 对应契约：智能体团队/第2轮-平台代卖-设计.md 第二节
--
-- 内容：
--   1) item 表补平台标记列：seller_type(1=个人 2=平台) / platform_point_id + 索引
--   2) 新建 platform_* 6 张表（代卖点 / 券模板 / 用户券 / 秒杀场次 / 场次商品 / 抢购记录）
--   3) 种子数据：平台官方账号(id=1000)、3 个代卖点（复制 trade_point 真实坐标）、
--      4 件平台商品（复制现有商品图片/分类，85 折）、3 张券、1 个秒杀场次 + 2 个秒杀商品
--
-- 幂等：可重复执行，第二遍不报错、不重复插入
--   - 建表用 CREATE TABLE IF NOT EXISTS
--   - ALTER / CREATE INDEX 前查 information_schema，用动态 SQL 执行
--   - 种子数据用「用户变量计数守卫」/ 显式 id + ON DUPLICATE KEY UPDATE
--     （MySQL 不允许 INSERT ... SELECT 的子查询里再引用目标表，所以用 SET @x := (SELECT COUNT ...) 的写法）
--
-- 用法（由 Lead 统一执行）：
--   mysql --host=127.0.0.1 --port=3306 --user=ershou --password=*** --database=ershou --default-character-set=utf8mb4 < sql/migration-platform.sql
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 1. item 表补充平台标记列（个人商品默认 1，历史数据自动回填默认值）
-- -------------------------------------------------------------
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND COLUMN_NAME = 'seller_type') = 0,
  'ALTER TABLE item ADD COLUMN seller_type TINYINT NOT NULL DEFAULT 1 COMMENT ''卖家类型 1=个人卖家 2=平台自营(官方代卖)'' AFTER seller_id',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- platform_point_id 指向 platform_consign_point.id（不是 trade_point.id！两者 id 语义不同）
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND COLUMN_NAME = 'platform_point_id') = 0,
  'ALTER TABLE item ADD COLUMN platform_point_id BIGINT DEFAULT NULL COMMENT ''平台代卖/自提点 id(platform_consign_point.id)，个人商品为 NULL'' AFTER seller_type',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND INDEX_NAME = 'idx_item_platform') = 0,
  'ALTER TABLE item ADD KEY idx_item_platform (seller_type, status, campus_id)',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------
-- 2. 平台代卖点（管理员维护，软删 status=0）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform_consign_point (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  campus_id     BIGINT       NOT NULL COMMENT '校区ID',
  name          VARCHAR(64)  NOT NULL COMMENT '代卖/自提点名称',
  address       VARCHAR(255) NOT NULL COMMENT '详细地址',
  lng           DECIMAL(10,7) DEFAULT NULL COMMENT '经度(GCJ-02，高德系)',
  lat           DECIMAL(10,7) DEFAULT NULL COMMENT '纬度(GCJ-02，高德系)',
  open_hours    VARCHAR(64)  DEFAULT NULL COMMENT '营业时间，如 09:00-21:00',
  contact_phone VARCHAR(32)  DEFAULT NULL COMMENT '联系电话',
  guide_text    VARCHAR(255) DEFAULT NULL COMMENT '到点图文指引',
  cover_url     VARCHAR(255) DEFAULT NULL COMMENT '点位封面图',
  sort          INT          NOT NULL DEFAULT 0 COMMENT '排序，小的靠前',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用(软删)',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ppoint_campus (campus_id, status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台代卖/自提点（管理员维护）';

-- -------------------------------------------------------------
-- 3. 优惠券模板（本轮只做领取；下单抵扣下轮，见设计第八节）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform_coupon (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  campus_id        BIGINT        NOT NULL COMMENT '校区ID',
  name             VARCHAR(64)   NOT NULL COMMENT '券名称',
  type             TINYINT       NOT NULL DEFAULT 1 COMMENT '1=满减 2=折扣',
  threshold_amount DECIMAL(10,2) DEFAULT NULL COMMENT '满减门槛（type=1）',
  discount_amount  DECIMAL(10,2) DEFAULT NULL COMMENT '减免金额（type=1）',
  discount_rate    DECIMAL(4,2)  DEFAULT NULL COMMENT '折扣率（type=2），0.90=9 折',
  total_stock      INT           NOT NULL DEFAULT 0 COMMENT '总库存',
  per_user_limit   INT           NOT NULL DEFAULT 1 COMMENT '每人限领张数',
  start_at         DATETIME      NOT NULL COMMENT '生效开始',
  end_at           DATETIME      NOT NULL COMMENT '生效结束',
  status           TINYINT       NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_pcoupon_campus (campus_id, status, start_at, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台优惠券模板';

-- 用户已领券：唯一键 (coupon_id,user_id) 是"每人限领"的 DB 兜底
CREATE TABLE IF NOT EXISTS platform_coupon_user (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  coupon_id   BIGINT   NOT NULL,
  user_id     BIGINT   NOT NULL,
  status      TINYINT  NOT NULL DEFAULT 1 COMMENT '1=未用 2=已用 3=过期',
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  used_at     DATETIME DEFAULT NULL,
  order_id    BIGINT   DEFAULT NULL COMMENT '核销订单（本轮未接入抵扣，预留）',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pcoupon_user (coupon_id, user_id),
  KEY idx_pcoupon_user_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户已领平台优惠券';

-- -------------------------------------------------------------
-- 4. 秒杀场次与场次商品
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform_flash_sale (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  campus_id  BIGINT      NOT NULL COMMENT '校区ID',
  title      VARCHAR(80) NOT NULL COMMENT '场次标题',
  start_at   DATETIME    NOT NULL COMMENT '开始时间',
  end_at     DATETIME    NOT NULL COMMENT '结束时间',
  status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1=预告 2=进行中 3=结束',
  sort       INT         NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_pflash_campus (campus_id, status, start_at, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台秒杀场次';

CREATE TABLE IF NOT EXISTS platform_flash_sale_item (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  flash_sale_id  BIGINT        NOT NULL,
  item_id        BIGINT        NOT NULL COMMENT 'item.id（seller_type=2 的平台商品）',
  seckill_price  DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
  seckill_stock  INT           NOT NULL DEFAULT 0 COMMENT '秒杀总库存',
  sold_count     INT           NOT NULL DEFAULT 0 COMMENT '已抢件数（DB 真值显示用，Redis 为准）',
  per_user_limit INT           NOT NULL DEFAULT 1 COMMENT '每人限购',
  sort           INT           NOT NULL DEFAULT 0,
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pflash_item (flash_sale_id, item_id),
  KEY idx_pflash_item_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台秒杀场次商品';

-- 抢购成功记录：DB 真值，预热时用它重算 Redis 库存与 per-user 已购数
CREATE TABLE IF NOT EXISTS platform_seckill_record (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  item_id    BIGINT        NOT NULL,
  user_id    BIGINT        NOT NULL,
  order_id   BIGINT        DEFAULT NULL COMMENT '生成的面交单 id',
  price      DECIMAL(10,2) DEFAULT NULL COMMENT '成交价（秒杀价）',
  created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pseckill_item_user (item_id, user_id),
  KEY idx_pseckill_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台秒杀抢购成功记录';

-- =============================================================
-- 5. 种子数据（全部幂等）
-- =============================================================

-- -------------------------------------------------------------
-- 5.1 平台官方卖家账号（固定 id=1000，不可登录）
-- -------------------------------------------------------------
-- role_flags=16 是第 2 轮新增的「平台自营」位（1=普通 2=多库存商家 4=代卖员 8=运营，互不冲突）。
-- 不写入可登录手机号：phone 只是占位（非合法手机号），phone_hash 用设计约定的固定串，
-- 这样任何真实手机号都登录不到该账号。
INSERT INTO sys_user (id, phone, phone_hash, nickname, school_id, campus_id,
                      role_flags, identity_flag, shop_name, shop_intro,
                      credit_score, credit_level, status, publish_limit, chat_limit)
VALUES (1000, 'PLATFORM_OFFICIAL', SHA2('ershou-platform-seller', 256), '校园二手官方严选',
        1, 2, 16, 1, '校园二手官方严选', '平台官方代卖：官方质检、校内自提、售后无忧',
        100, 1, 1, 9999, 9999)
ON DUPLICATE KEY UPDATE
  nickname     = VALUES(nickname),
  role_flags   = VALUES(role_flags),
  identity_flag= VALUES(identity_flag),
  shop_name    = VALUES(shop_name),
  status       = 1;

-- -------------------------------------------------------------
-- 5.2 代卖点 3 个：从 trade_point 现有行复制真实坐标（campus_id=2）
-- -------------------------------------------------------------
SET @platform_point_count := (SELECT COUNT(*) FROM platform_consign_point);

INSERT INTO platform_consign_point
  (campus_id, name, address, lng, lat, open_hours, contact_phone, guide_text, cover_url, sort, status)
SELECT tp.campus_id,
       CONCAT(tp.name, ' · 官方代卖点'),
       CONCAT('校内自提：', tp.name, COALESCE(CONCAT('（', tp.guide_text, '）'), '')),
       tp.lng, tp.lat,
       '09:00-21:00',
       '0871-6510000',
       COALESCE(tp.guide_text, CONCAT('从东门进入后前往', tp.name)),
       NULL,
       ROW_NUMBER() OVER (ORDER BY tp.id),
       1
  FROM trade_point tp
 WHERE tp.campus_id = 2
   AND tp.status = 1
   AND tp.audit_status = 1
   AND tp.lng IS NOT NULL
   AND tp.lat IS NOT NULL
   AND @platform_point_count = 0
 ORDER BY tp.id
 LIMIT 3;

-- 平台商品默认挂在该校区排序第一的代卖点上（每个校区取一个，单校区场景即唯一）
SET @platform_point_id := (SELECT p.id FROM platform_consign_point p
                            WHERE p.campus_id = 2 AND p.status = 1
                            ORDER BY p.sort, p.id LIMIT 1);

-- -------------------------------------------------------------
-- 5.3 平台商品 4 件：复制现有个人商品的图片/分类/成色，价格 85 折
-- -------------------------------------------------------------
-- 说明：设计写的是"从现有在售商品复制"。这里放宽了状态过滤（只要求有封面图），
-- 因为迁移可能在后台「商品过期」定时任务把老商品置为已过期之后才执行；先按在售优先排序，
-- 不够再取其他状态，保证种子 4 件稳定存在。只插一次由 @platform_item_count 守卫。
SET @platform_item_count := (SELECT COUNT(*) FROM item WHERE seller_type = 2);

INSERT INTO item
  (seller_id, school_id, campus_id, seller_type, platform_point_id,
   title, description, category_id, condition_level,
   price, original_price, negotiable, stock, is_unique,
   cover_url, images, trade_mode, prefer_point_id,
   status, audit_result, expire_at, published_at,
   last_live_title, last_live_desc_hash,
   view_count, chat_count, fav_count, version)
SELECT 1000,
       i.school_id,
       COALESCE(i.campus_id, 2),
       2,
       COALESCE(@platform_point_id,
                (SELECT p.id FROM platform_consign_point p WHERE p.campus_id = COALESCE(i.campus_id, 2)
                  ORDER BY p.sort, p.id LIMIT 1)),
       LEFT(CONCAT('[官方严选] ', i.title), 80),
       i.description,
       i.category_id,
       i.condition_level,
       ROUND(i.price * 0.85, 2),
       i.price,
       0,
       GREATEST(COALESCE(i.stock, 1), 1),
       CASE WHEN COALESCE(i.stock, 1) <= 1 THEN 1 ELSE 0 END,
       i.cover_url,
       i.images,
       1,
       NULL,
       2,
       1,
       DATE_ADD(NOW(), INTERVAL 30 DAY),
       NOW(),
       LEFT(CONCAT('[官方严选] ', i.title), 80),
       IF(i.description IS NULL OR i.description = '', NULL, MD5(i.description)),
       0, 0, 0, 0
  FROM item i
 WHERE i.seller_id <> 1000
   AND i.seller_type = 1
   AND i.cover_url IS NOT NULL
   AND i.category_id IS NOT NULL
   AND @platform_item_count = 0
 ORDER BY (i.status = 2) DESC, i.id
 LIMIT 4;

-- -------------------------------------------------------------
-- 5.4 优惠券 3 张（满50减8 / 满100减20 / 满30打9折）
-- -------------------------------------------------------------
-- 显式 id：重复执行走 ON DUPLICATE KEY UPDATE（只刷新时间窗和启用状态，不动库存，避免把已领的券重置）
INSERT INTO platform_coupon
  (id, campus_id, name, type, threshold_amount, discount_amount, discount_rate,
   total_stock, per_user_limit, start_at, end_at, status)
VALUES
  (1, 2, '满 50 减 8',   1, 50.00,  8.00,  NULL,  100, 1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), 1),
  (2, 2, '满 100 减 20', 1, 100.00, 20.00, NULL,  50,  1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), 1),
  (3, 2, '满 30 打 9 折', 2, 30.00, NULL,  0.90, 100, 1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), 1)
ON DUPLICATE KEY UPDATE
  start_at = VALUES(start_at),
  end_at   = VALUES(end_at),
  status   = VALUES(status);

-- -------------------------------------------------------------
-- 5.5 秒杀场次 1 个（进行中）+ 2 个秒杀商品（平台商品 6 折，库存 5）
-- -------------------------------------------------------------
INSERT INTO platform_flash_sale (id, campus_id, title, start_at, end_at, status, sort)
VALUES (1, 2, '官方严选 · 限时秒杀', DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_ADD(NOW(), INTERVAL 3 DAY), 2, 1)
ON DUPLICATE KEY UPDATE
  title    = VALUES(title),
  start_at = VALUES(start_at),
  end_at   = VALUES(end_at),
  status   = VALUES(status),
  sort     = VALUES(sort);

SET @platform_flash_item_count := (SELECT COUNT(*) FROM platform_flash_sale_item);

INSERT INTO platform_flash_sale_item
  (flash_sale_id, item_id, seckill_price, seckill_stock, sold_count, per_user_limit, sort)
SELECT 1,
       i.id,
       ROUND(i.price * 0.6, 2),
       5,
       0,
       1,
       ROW_NUMBER() OVER (ORDER BY i.id)
  FROM item i
 WHERE i.seller_type = 2
   AND i.seller_id = 1000
   AND @platform_flash_item_count = 0
 ORDER BY i.id
 LIMIT 2;

-- =============================================================
-- 6. 修正：秒杀场次商品的 item.stock 必须 >= 场次 seckill_stock
-- -------------------------------------------------------------
-- 背景：5.3 从个人商品克隆时，源商品都是孤品 stock=1；而 5.5 场次商品是
-- seckill_stock=5。若不放宽 item.stock，秒杀第 1 人抢到就把 item 打到
-- stock=0/status=4，第 2 人起必然 30004，多份秒杀退化成"1 份"（QA 验收发现）。
-- 幂等：仅在"该商品还没有任何抢购记录"时抬高库存；已有成交后重跑不改库存，
-- 避免把卖掉的份数刷回去。
-- =============================================================
UPDATE item i
  JOIN platform_flash_sale_item fsi ON fsi.item_id = i.id
   SET i.stock = GREATEST(i.stock, fsi.seckill_stock),
       i.is_unique = 0
 WHERE i.seller_type = 2
   AND NOT EXISTS (SELECT 1 FROM platform_seckill_record r WHERE r.item_id = i.id);

-- =============================================================
-- 7. 迁移自检（只读，输出给执行者确认；不影响幂等）
-- =============================================================
SELECT 'item.seller_type' AS check_item, COUNT(*) AS ok
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND COLUMN_NAME = 'seller_type'
UNION ALL
SELECT 'item.platform_point_id', COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND COLUMN_NAME = 'platform_point_id'
UNION ALL
SELECT 'platform_tables(6)', COUNT(*) FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'platform\_%'
UNION ALL
SELECT 'platform_account(1000)', COUNT(*) FROM sys_user WHERE id = 1000
UNION ALL
SELECT 'platform_points', COUNT(*) FROM platform_consign_point
UNION ALL
SELECT 'platform_items', COUNT(*) FROM item WHERE seller_type = 2
UNION ALL
SELECT 'platform_coupons', COUNT(*) FROM platform_coupon
UNION ALL
SELECT 'platform_flash_sales', COUNT(*) FROM platform_flash_sale
UNION ALL
SELECT 'platform_flash_items', COUNT(*) FROM platform_flash_sale_item;
