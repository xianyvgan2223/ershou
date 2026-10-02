-- =============================================================
-- 上架审核（item_review）：运营后台「上架审核」页的数据表
-- =============================================================
-- 背景（产品口径，2026-10-02）：
--   ① 商品正常上架【不拦截、不等待】：发布立即在售，与之前完全一致；
--   ② 但每条"上架"都要出现在运营的「上架审核」页里，由审核员逐条确认，
--      点「已审核」后从该页消失 —— 这个页面保证"每条商品都被人工看过"；
--   ③ 商品在售期间被【改名/改描述】也要重新进入该页（内容变了，等于新内容）；
--   ④ 违规下架(8) 的商品二次申请上架【必须审核】，通过前不对外可见。
--
-- 为什么单独建表而不是给 item 加字段：
--   审核状态与商品生命周期状态是两件事（商品在售多久就要被审多久，
--   而且"谁在什么时候审的"要能单独查），拆开互不干扰，
--   也避免 item 表继续膨胀（它已经有 30 列）。
-- =============================================================

CREATE TABLE IF NOT EXISTS item_review (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  item_id        BIGINT       NOT NULL COMMENT '商品 id（一个商品只保留一条当前审核记录）',
  seller_id      BIGINT       DEFAULT NULL COMMENT '卖家 id，冗余便于按人筛选',
  title          TEXT         DEFAULT NULL COMMENT '入队时的标题快照（事后改名也能看到当时审的是什么）',
  review_type    TINYINT      NOT NULL DEFAULT 1 COMMENT '1=新上架 2=改名/改描述 3=到期重新上架 4=违规后二次申请 5=存量导入',
  revision       INT          NOT NULL DEFAULT 1 COMMENT '内容修订号：同一商品每次重新入队 +1',
  status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待审核 1=已审核（已经处理过，默认列表不再显示）',
  review_action  TINYINT      DEFAULT NULL COMMENT '1=已审核通过 2=已下架（审核员的处置动作）',
  queued_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本轮入队时间（列表按它倒序）',
  first_queued_at DATETIME    DEFAULT NULL COMMENT '该商品第一次进入审核页的时间',
  content_changed_at DATETIME DEFAULT NULL COMMENT '最近一次改名/改描述时间',
  reviewer_id    BIGINT       DEFAULT NULL COMMENT '审核人；待审时表示当前认领人',
  claimed_at     DATETIME     DEFAULT NULL COMMENT '认领时间',
  reviewed_at    DATETIME     DEFAULT NULL COMMENT '处置时间',
  note           VARCHAR(255) DEFAULT NULL COMMENT '审核备注 / 下架原因',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_item (item_id),
  KEY idx_pending (status, queued_at),
  KEY idx_seller (seller_id, queued_at)
) ENGINE=InnoDB COMMENT='上架审核队列（运营逐条确认，保证每条商品都被看过）';

-- -------------------------------------------------------------
-- item 表补充"上架快照"：用来判断在售期间的改名/改描述
-- -------------------------------------------------------------
-- last_live_title / last_live_desc_hash 记录的是"审核员已经看过的那一版内容"。
-- 改名后两者对不上即视为新内容 → 重新入队。
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND COLUMN_NAME = 'last_live_title') = 0,
  'ALTER TABLE item ADD COLUMN last_live_title VARCHAR(120) DEFAULT NULL COMMENT ''上架时（审核员已看过）的标题快照''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND COLUMN_NAME = 'last_live_desc_hash') = 0,
  'ALTER TABLE item ADD COLUMN last_live_desc_hash CHAR(32) DEFAULT NULL COMMENT ''上架时（审核员已看过）的描述哈希（MD5），用来判断描述是否改过''',
  'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------
-- 存量数据回填：已经在售的商品按「已审核」入册，不打扰审核员
-- -------------------------------------------------------------
-- 为什么按已审核回填而不是全部待审：库里已有的在售商品是历史数据，
-- 一次性砸几百条待审进列表，审核员根本分不清"哪些是新来的"，
-- 实时推送的意义也没了。回填成已审核 = 有档案、不刷屏。
-- 需要重新过一遍存量时：DELETE FROM item_review; 然后重启后端（启动任务会重新入册）。
INSERT INTO item_review (item_id, seller_id, title, review_type, revision, status,
                         queued_at, first_queued_at, reviewed_at, note,
                         created_at, updated_at)
SELECT i.id, i.seller_id, i.title, 5, 1, 1,
       IFNULL(i.published_at, i.created_at), i.created_at, i.created_at, '存量商品导入（未逐条复核）',
       NOW(), NOW()
  FROM item i
 WHERE i.status IN (2, 3)
   AND NOT EXISTS (SELECT 1 FROM (SELECT item_id FROM item_review) r WHERE r.item_id = i.id);

-- 存量商品的上架快照按当前内容补齐，这样下次改名才会被识别成"内容变了"
UPDATE item
   SET last_live_title = title,
       last_live_desc_hash = IF(description IS NULL OR description = '', NULL, MD5(description))
 WHERE status IN (2, 3)
   AND last_live_title IS NULL;
