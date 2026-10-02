-- =============================================================
-- 校园二手交易平台 · MySQL 8.0 建表脚本
-- 对应《校园二手交易平台需求文档》V1.1
-- 字符集 utf8mb4 / 引擎 InnoDB
-- 说明：不使用反引号，避免保留字冲突；已避开 read/order/rank/condition/key 等保留字
-- =============================================================
CREATE DATABASE IF NOT EXISTS ershou DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE ershou;

-- -------------------------------------------------------------
-- 1. 学校与校区
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS school (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name          VARCHAR(64)  NOT NULL COMMENT '学校名称',
  city          VARCHAR(32)  DEFAULT NULL COMMENT '城市',
  source        TINYINT      NOT NULL DEFAULT 1 COMMENT '来源 1=平台开通 2=用户申请',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_school_name (name)
) ENGINE=InnoDB COMMENT='学校';

CREATE TABLE IF NOT EXISTS campus (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  school_id     BIGINT       NOT NULL COMMENT '学校ID',
  name          VARCHAR(64)  NOT NULL COMMENT '校区名称',
  map_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=自绘平面图 2=第三方地图(高德/腾讯)',
  map_config    JSON         DEFAULT NULL COMMENT '底图配置: {imgUrl,width,height,minZoom,maxZoom,center,polygon}',
  status        TINYINT      NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_campus_school (school_id)
) ENGINE=InnoDB COMMENT='校区';

-- -------------------------------------------------------------
-- 2. 面交点（校内地图核心）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_point (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  campus_id      BIGINT       NOT NULL,
  name           VARCHAR(64)  NOT NULL COMMENT '点位名称',
  source         TINYINT      NOT NULL DEFAULT 1 COMMENT '来源 1=官方 2=用户提交 3=高德搜索落库',
  point_type     TINYINT      NOT NULL DEFAULT 9 COMMENT '1=门卫室旁 2=食堂 3=广场 4=快递站 5=教学楼大厅 6=校警室旁 7=宿舍区 9=其他',
  coord_x        DECIMAL(10,2) DEFAULT NULL COMMENT '自绘底图坐标X(像素)',
  coord_y        DECIMAL(10,2) DEFAULT NULL COMMENT '自绘底图坐标Y(像素)',
  lng            DECIMAL(10,7) DEFAULT NULL COMMENT '经度(第三方地图)',
  lat            DECIMAL(10,7) DEFAULT NULL COMMENT '纬度(第三方地图)',
  available_period JSON       DEFAULT NULL COMMENT '可用时段 ["08:00-22:00"]',
  safe_tags      JSON         DEFAULT NULL COMMENT '安全标签 ["监控","人流","照明"]',
  safety_score   TINYINT      NOT NULL DEFAULT 60 COMMENT '安全分 0-100',
  guide_text     VARCHAR(255) DEFAULT NULL COMMENT '图文参照说明: 从东门进直走100米',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  audit_status   TINYINT      NOT NULL DEFAULT 1 COMMENT '0=待审 1=通过 2=驳回',
  merge_group    BIGINT       DEFAULT NULL COMMENT '合并组ID(同名点合并)',
  deal_count     INT          NOT NULL DEFAULT 0 COMMENT '累计成交',
  dispute_count  INT          NOT NULL DEFAULT 0 COMMENT '累计纠纷',
  creator_id     BIGINT       DEFAULT NULL COMMENT '提交用户',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_point_campus_status (campus_id, status),
  KEY idx_point_audit (audit_status),
  KEY idx_point_geo (campus_id, lng, lat)
) ENGINE=InnoDB COMMENT='校内面交点';

-- -------------------------------------------------------------
-- 3. 用户（轻注册：无实名 / 无学籍字段）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  phone                VARCHAR(64)  NOT NULL COMMENT '手机号(AES加密存储)',
  phone_hash           CHAR(64)     NOT NULL COMMENT '手机号SHA256(用于唯一索引与查询)',
  nickname             VARCHAR(32)  NOT NULL COMMENT '昵称',
  avatar               VARCHAR(255) DEFAULT NULL,
  school_id            BIGINT       DEFAULT NULL,
  campus_id            BIGINT       DEFAULT NULL,
  role_flags           INT          NOT NULL DEFAULT 0 COMMENT '位标记 1=普通 2=多库存商家 4=代卖员 8=运营',
  identity_flag        TINYINT      NOT NULL DEFAULT 0 COMMENT '身份徽章 0=普通 1=商家 2=代卖员 4=校内邮箱已验证(可选)',
  shop_name            VARCHAR(64)  DEFAULT NULL COMMENT '商家商铺名',
  shop_intro           VARCHAR(255) DEFAULT NULL COMMENT '商家售后说明',
  license_verified     TINYINT      NOT NULL DEFAULT 0 COMMENT '营业执照徽章(可选) 0/1',
  credit_score         INT          NOT NULL DEFAULT 100 COMMENT '信用分',
  credit_level         TINYINT      NOT NULL DEFAULT 2 COMMENT '1=优秀 2=良好 3=一般 4=风险',
  risk_score           INT          NOT NULL DEFAULT 0,
  risk_tags            JSON         DEFAULT NULL,
  device_id            VARCHAR(64)  DEFAULT NULL COMMENT '设备指纹',
  device_account_count INT          NOT NULL DEFAULT 1 COMMENT '同设备账号数',
  publish_limit        INT          NOT NULL DEFAULT 5 COMMENT '每日发布上限(新号限额)',
  chat_limit           INT          NOT NULL DEFAULT 20 COMMENT '每日私聊上限',
  status               TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 2=限权 3=封禁',
  ban_until            DATETIME     DEFAULT NULL,
  last_login_at        DATETIME     DEFAULT NULL,
  created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_phone_hash (phone_hash),
  KEY idx_user_campus (campus_id),
  KEY idx_user_credit (credit_score),
  KEY idx_user_device (device_id)
) ENGINE=InnoDB COMMENT='用户(轻注册)';

-- -------------------------------------------------------------
-- 4. 分类与商品
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS item_category (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  parent_id  BIGINT      NOT NULL DEFAULT 0,
  name       VARCHAR(32) NOT NULL,
  icon       VARCHAR(255) DEFAULT NULL,
  sort       INT         NOT NULL DEFAULT 0,
  school_id  BIGINT      DEFAULT NULL COMMENT 'NULL=通用分类',
  status     TINYINT     NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  KEY idx_cat_parent (parent_id)
) ENGINE=InnoDB COMMENT='商品分类';

CREATE TABLE IF NOT EXISTS item (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  seller_id       BIGINT        NOT NULL,
  school_id       BIGINT        DEFAULT NULL,
  campus_id       BIGINT        DEFAULT NULL,
  title           VARCHAR(80)   NOT NULL,
  description     TEXT,
  category_id     BIGINT        NOT NULL,
  condition_level TINYINT       NOT NULL DEFAULT 3 COMMENT '1=全新 2=几乎全新 3=轻微使用 4=明显使用 5=有瑕疵',
  price           DECIMAL(10,2) NOT NULL,
  original_price  DECIMAL(10,2) DEFAULT NULL,
  negotiable      TINYINT       NOT NULL DEFAULT 1 COMMENT '是否可议价',
  stock           INT           NOT NULL DEFAULT 1,
  is_unique       TINYINT       NOT NULL DEFAULT 1 COMMENT '孤品标记',
  cover_url       VARCHAR(255)  DEFAULT NULL,
  images          JSON          DEFAULT NULL COMMENT '图片URL数组 1-9',
  video_url       VARCHAR(255)  DEFAULT NULL,
  trade_mode      TINYINT       NOT NULL DEFAULT 1 COMMENT '1=仅面交 2=面交+邮寄(预留)',
  prefer_point_id BIGINT        DEFAULT NULL COMMENT '常用面交点',
  status          TINYINT       NOT NULL DEFAULT 0 COMMENT '0=草稿 1=审核中 2=在售 3=已锁定 4=已售出 5=已下架 6=审核驳回 7=已过期 8=违规下架',
  audit_result    TINYINT       DEFAULT NULL COMMENT '1=通过 2=驳回',
  audit_reason    VARCHAR(255)  DEFAULT NULL,
  reject_code     VARCHAR(32)   DEFAULT NULL,
  lock_user_id    BIGINT        DEFAULT NULL COMMENT '当前锁库用户',
  lock_expire_at  DATETIME      DEFAULT NULL COMMENT '锁库过期时间(30分钟)',
  last_live_title     VARCHAR(120) DEFAULT NULL COMMENT '上架时（审核员已看过）的标题快照，用于识别在售期间改名',
  last_live_desc_hash CHAR(32)     DEFAULT NULL COMMENT '上架时的描述 MD5，用于识别在售期间改描述',
  expire_at       DATETIME      DEFAULT NULL COMMENT '商品有效期截止(默认发布后 7 天，见 ershou.item.valid-days)',
  published_at    DATETIME      DEFAULT NULL,
  view_count      INT           NOT NULL DEFAULT 0,
  chat_count      INT           NOT NULL DEFAULT 0,
  fav_count       INT           NOT NULL DEFAULT 0,
  version         INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_item_seller (seller_id, status),
  KEY idx_item_campus_status (campus_id, status, published_at),
  KEY idx_item_category (category_id, status),
  KEY idx_item_lock (status, lock_expire_at),
  KEY idx_item_expire (status, expire_at),
  KEY idx_item_point (prefer_point_id),
  FULLTEXT KEY ft_item_title (title, description) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='商品';

-- -------------------------------------------------------------
-- 5. 收藏 / 购物车
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS favorite_folder (
  id        BIGINT      NOT NULL AUTO_INCREMENT,
  user_id   BIGINT      NOT NULL,
  name      VARCHAR(32) NOT NULL,
  sort      INT         NOT NULL DEFAULT 0,
  created_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_folder_user (user_id)
) ENGINE=InnoDB COMMENT='收藏夹分组';

CREATE TABLE IF NOT EXISTS favorite (
  id        BIGINT   NOT NULL AUTO_INCREMENT,
  user_id   BIGINT   NOT NULL,
  item_id   BIGINT   NOT NULL,
  folder_id BIGINT   DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fav_user_item (user_id, item_id),
  KEY idx_fav_item (item_id)
) ENGINE=InnoDB COMMENT='收藏';

CREATE TABLE IF NOT EXISTS cart_item (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  user_id    BIGINT   NOT NULL,
  item_id    BIGINT   NOT NULL,
  seller_id  BIGINT   NOT NULL COMMENT '冗余: 便于按卖家分组合并面交',
  quantity   INT      NOT NULL DEFAULT 1,
  spec_text  VARCHAR(64) DEFAULT NULL COMMENT '规格/成色备注',
  selected   TINYINT  NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cart_user_item (user_id, item_id),
  KEY idx_cart_user_seller (user_id, seller_id)
) ENGINE=InnoDB COMMENT='购物车(面交意向清单)';

-- -------------------------------------------------------------
-- 6. 面交单（核心交易凭证）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meetup_order (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  order_no         VARCHAR(32)  NOT NULL COMMENT '业务单号',
  batch_no         VARCHAR(32)  DEFAULT NULL COMMENT '合并面交行程号(跨卖家共享)',
  buyer_id         BIGINT       NOT NULL,
  seller_id        BIGINT       NOT NULL,
  item_id          BIGINT       NOT NULL,
  item_title       VARCHAR(80)  NOT NULL COMMENT '快照',
  item_cover       VARCHAR(255) DEFAULT NULL COMMENT '快照',
  quantity         INT          NOT NULL DEFAULT 1,
  deal_price       DECIMAL(10,2) DEFAULT NULL COMMENT '实际成交价(可选填写)',
  point_id         BIGINT       DEFAULT NULL COMMENT '面交点(可空=待协商)',
  point_snapshot   JSON         DEFAULT NULL COMMENT '点位快照(名称/坐标/指引,防篡改)',
  time_slot_start  DATETIME     DEFAULT NULL COMMENT '时间窗开始(可空=待协商)',
  time_slot_end    DATETIME     DEFAULT NULL COMMENT '时间窗结束(可空=待协商)',
  status           TINYINT      NOT NULL DEFAULT 1 COMMENT '1=待卖家确认 2=待面交 3=已完成 4=已取消 5=已失效 6=爽约 7=纠纷中',
  verify_code      CHAR(6)      DEFAULT NULL COMMENT '6位核销码',
  verify_expire_at DATETIME     DEFAULT NULL,
  verified_at      DATETIME     DEFAULT NULL,
  verified_by      BIGINT       DEFAULT NULL COMMENT '核销发起人',
  buyer_confirm_at DATETIME     DEFAULT NULL,
  seller_confirm_at DATETIME    DEFAULT NULL,
  reschedule_count INT          NOT NULL DEFAULT 0,
  cancel_by        BIGINT       DEFAULT NULL,
  cancel_reason    VARCHAR(255) DEFAULT NULL,
  noshow_user_id   BIGINT       DEFAULT NULL,
  remark           VARCHAR(255) DEFAULT NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_order_buyer (buyer_id, status, created_at),
  KEY idx_order_seller (seller_id, status, created_at),
  KEY idx_order_item (item_id),
  KEY idx_order_batch (batch_no),
  KEY idx_order_slot (status, time_slot_start)
) ENGINE=InnoDB COMMENT='面交单';

-- -------------------------------------------------------------
-- 7. 私聊
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_session (
  id           BIGINT   NOT NULL AUTO_INCREMENT,
  user_a       BIGINT   NOT NULL COMMENT '较小的用户ID',
  user_b       BIGINT   NOT NULL COMMENT '较大的用户ID',
  item_id      BIGINT   DEFAULT NULL COMMENT '来源商品',
  last_msg_id  BIGINT   DEFAULT NULL,
  last_msg_text VARCHAR(120) DEFAULT NULL,
  last_msg_at  DATETIME DEFAULT NULL,
  unread_a     INT      NOT NULL DEFAULT 0,
  unread_b     INT      NOT NULL DEFAULT 0,
  risk_level   TINYINT  NOT NULL DEFAULT 0 COMMENT '会话风险等级',
  status       TINYINT  NOT NULL DEFAULT 1 COMMENT '1=可发送（canSend 判据，勿与"已删除"混用）',
  deleted_a    TINYINT  NOT NULL DEFAULT 0 COMMENT 'user_a 是否已从自己列表删除该会话（每用户软删除）',
  deleted_b    TINYINT  NOT NULL DEFAULT 0 COMMENT 'user_b 是否已从自己列表删除该会话',
  deleted_a_at DATETIME DEFAULT NULL COMMENT 'user_a 删除时间',
  deleted_b_at DATETIME DEFAULT NULL COMMENT 'user_b 删除时间',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_pair_item (user_a, user_b, item_id),
  KEY idx_session_a (user_a, last_msg_at),
  KEY idx_session_b (user_b, last_msg_at),
  KEY idx_session_user_a_del (user_a, deleted_a),
  KEY idx_session_user_b_del (user_b, deleted_b)
) ENGINE=InnoDB COMMENT='会话';

CREATE TABLE IF NOT EXISTS chat_message (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  session_id  BIGINT      NOT NULL,
  sender_id   BIGINT      NOT NULL,
  receiver_id BIGINT      NOT NULL,
  item_id     BIGINT      DEFAULT NULL COMMENT '商品卡片',
  msg_type    TINYINT     NOT NULL DEFAULT 1 COMMENT '1=文本 2=图片 3=位置 4=商品卡片 5=面交单卡片 6=系统消息',
  content     TEXT        NOT NULL COMMENT '内容(AES加密,图片存URL数组)',
  risk_level  TINYINT     NOT NULL DEFAULT 0,
  blocked     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否被风控拦截',
  recalled    TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已撤回（发送方限定时间内可撤回）',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_msg_session (session_id, id),
  KEY idx_msg_risk (risk_level, created_at)
) ENGINE=InnoDB COMMENT='聊天消息';

-- -------------------------------------------------------------
-- 8. 评价与信用
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review (
  id             BIGINT   NOT NULL AUTO_INCREMENT,
  order_id       BIGINT   NOT NULL,
  from_user_id   BIGINT   NOT NULL,
  to_user_id     BIGINT   NOT NULL,
  item_id        BIGINT   DEFAULT NULL,
  score_desc     TINYINT  NOT NULL COMMENT '描述相符 1-5',
  score_attitude TINYINT  NOT NULL COMMENT '沟通态度 1-5',
  score_punctual TINYINT  NOT NULL COMMENT '守时 1-5',
  score_total    TINYINT  NOT NULL COMMENT '总体 1-5',
  tags           JSON     DEFAULT NULL,
  content        VARCHAR(500) DEFAULT NULL,
  images         JSON     DEFAULT NULL,
  anonymous      TINYINT  NOT NULL DEFAULT 1 COMMENT '盲评:双方评完或7天后可见',
  status         TINYINT  NOT NULL DEFAULT 1 COMMENT '1=正常 2=隐藏(申诉成立)',
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_order_from (order_id, from_user_id),
  KEY idx_review_to (to_user_id, status, created_at)
) ENGINE=InnoDB COMMENT='双向评价';

CREATE TABLE IF NOT EXISTS credit_log (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  user_id      BIGINT      NOT NULL,
  change_value INT         NOT NULL COMMENT '分数变化(正负)',
  before_score INT         NOT NULL,
  after_score  INT         NOT NULL,
  reason       VARCHAR(64) NOT NULL,
  biz_type     VARCHAR(32) DEFAULT NULL,
  biz_id       BIGINT      DEFAULT NULL,
  operator_id  BIGINT      DEFAULT NULL COMMENT '运营手动调整时记录',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_credit_user (user_id, created_at)
) ENGINE=InnoDB COMMENT='信用分流水';

-- -------------------------------------------------------------
-- 9. 举报 / 申诉
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS report_ticket (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  reporter_id   BIGINT       NOT NULL,
  target_type   TINYINT      NOT NULL COMMENT '1=商品 2=用户 3=会话 4=面交单',
  target_id     BIGINT       NOT NULL,
  report_type   TINYINT      NOT NULL COMMENT '1=虚假信息 2=货不对板 3=诈骗 4=辱骂 5=违禁品 6=爽约 7=盗图 8=商家违规 9=其他',
  description   VARCHAR(500) DEFAULT NULL,
  images        JSON         DEFAULT NULL,
  evidence      JSON         DEFAULT NULL COMMENT '系统聚合证据: 商品快照/聊天记录/核销/位置',
  ai_summary    TEXT         DEFAULT NULL COMMENT 'AI 仲裁摘要',
  ai_suggest    VARCHAR(64)  DEFAULT NULL COMMENT 'AI 责任倾向建议(仅供人工参考)',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=待受理 2=处理中 3=已判定 4=申诉中 5=已结案',
  decision      VARCHAR(255) DEFAULT NULL,
  penalty       VARCHAR(255) DEFAULT NULL,
  handler_id    BIGINT       DEFAULT NULL,
  first_reply_at DATETIME    DEFAULT NULL,
  closed_at     DATETIME     DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_report_status (status, created_at),
  KEY idx_report_target (target_type, target_id)
) ENGINE=InnoDB COMMENT='举报/仲裁工单';

-- -------------------------------------------------------------
-- 10. 代卖（毕业季预留）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consign_order (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  order_no         VARCHAR(32)   NOT NULL,
  owner_id         BIGINT        NOT NULL COMMENT '委托人',
  agent_id         BIGINT        DEFAULT NULL COMMENT '代卖员',
  campus_id        BIGINT        DEFAULT NULL,
  title            VARCHAR(80)   NOT NULL,
  category_id      BIGINT        DEFAULT NULL,
  images           JSON          DEFAULT NULL,
  expected_price   DECIMAL(10,2) DEFAULT NULL COMMENT '委托人期望价',
  appraise_low     DECIMAL(10,2) DEFAULT NULL COMMENT 'AI 估价下限',
  appraise_high    DECIMAL(10,2) DEFAULT NULL,
  commission_rate  DECIMAL(5,2)  NOT NULL DEFAULT 10.00 COMMENT '佣金比例(%)',
  quality_report   JSON          DEFAULT NULL COMMENT '质检报告: 外观/功能/序列号/瑕疵 图片',
  status           TINYINT       NOT NULL DEFAULT 1 COMMENT '1=待收件 2=质检中 3=已上架 4=已售出 5=已结算 6=已拒收 7=已到期 8=退回中 9=已捐赠',
  item_id          BIGINT        DEFAULT NULL COMMENT '上架后的商品ID',
  delivery_proof   JSON          DEFAULT NULL COMMENT '收货/交付凭证',
  settle_amount    DECIMAL(10,2) DEFAULT NULL COMMENT '结算金额(成交价-佣金)',
  settle_at        DATETIME      DEFAULT NULL,
  unsold_policy    TINYINT       NOT NULL DEFAULT 1 COMMENT '1=退回 2=延卖 3=捐赠 4=回收',
  agreement_sign_at DATETIME     DEFAULT NULL COMMENT '电子委托协议签署时间',
  expire_at        DATETIME      DEFAULT NULL COMMENT '上架到期(默认30天)',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_consign_no (order_no),
  KEY idx_consign_owner (owner_id, status),
  KEY idx_consign_agent (agent_id, status)
) ENGINE=InnoDB COMMENT='代卖委托单';

-- -------------------------------------------------------------
-- 11. AI 审核记录 / 通知 / 运营审计
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_audit_record (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  biz_type      VARCHAR(32)  NOT NULL COMMENT 'item/item_image/chat/report/shop_license',
  biz_id        BIGINT       NOT NULL,
  model_version VARCHAR(32)  DEFAULT NULL,
  labels        JSON         DEFAULT NULL COMMENT '命中标签及分数',
  score         DECIMAL(5,4) DEFAULT NULL COMMENT '最高置信度',
  decision      TINYINT      NOT NULL COMMENT '1=放行 2=拦截 3=转人工',
  reason        VARCHAR(255) DEFAULT NULL,
  operator_id   BIGINT       DEFAULT NULL COMMENT '人审操作人',
  appeal_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0=无 1=申诉中 2=维持 3=改判',
  cost_ms       INT          DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ai_biz (biz_type, biz_id),
  KEY idx_ai_decision (decision, created_at)
) ENGINE=InnoDB COMMENT='AI 审核记录';

CREATE TABLE IF NOT EXISTS notification (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  user_id    BIGINT       NOT NULL,
  notify_type TINYINT     NOT NULL COMMENT '1=互动 2=交易 3=系统 4=审核结果 5=信用变动',
  title      VARCHAR(64)  NOT NULL,
  content    VARCHAR(500) NOT NULL,
  biz_type   VARCHAR(32)  DEFAULT NULL,
  biz_id     BIGINT       DEFAULT NULL,
  channel    VARCHAR(32)  NOT NULL DEFAULT 'inapp' COMMENT 'inapp/push/sms/wechat',
  is_read    TINYINT      NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_notify_user (user_id, is_read, created_at)
) ENGINE=InnoDB COMMENT='消息通知';

-- -------------------------------------------------------------
-- 21. 拉黑（PRD FR-UP-04 / FR-IM-07：拉黑后双向不可私聊）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_block (
  id              BIGINT   NOT NULL AUTO_INCREMENT,
  user_id         BIGINT   NOT NULL COMMENT '发起拉黑的人',
  blocked_user_id BIGINT   NOT NULL COMMENT '被拉黑的人',
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_block_pair (user_id, blocked_user_id),
  KEY idx_block_target (blocked_user_id)
) ENGINE=InnoDB COMMENT='用户拉黑';

CREATE TABLE IF NOT EXISTS sys_audit_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  operator_id  BIGINT       NOT NULL,
  module       VARCHAR(32)  NOT NULL,
  action       VARCHAR(32)  NOT NULL,
  target_type  VARCHAR(32)  DEFAULT NULL,
  target_id    BIGINT       DEFAULT NULL,
  before_value JSON         DEFAULT NULL,
  after_value  JSON         DEFAULT NULL,
  ip           VARCHAR(45)  DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_operator (operator_id, created_at),
  KEY idx_audit_target (target_type, target_id)
) ENGINE=InnoDB COMMENT='运营操作审计日志';

-- -------------------------------------------------------------
-- 19.5 上架审核队列（运营后台「上架审核」页）
-- -------------------------------------------------------------
-- 商品上架不拦截（发布立即在售），但每条上架都要出现在运营的「上架审核」页
-- 由审核员逐条确认；在售期间改名/改描述要重新入队；违规下架后二次申请上架必须审核。
CREATE TABLE IF NOT EXISTS item_review (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  item_id            BIGINT       NOT NULL COMMENT '商品 id（一个商品只保留一条当前审核记录）',
  seller_id          BIGINT       DEFAULT NULL COMMENT '卖家 id，冗余便于按人筛选',
  title              TEXT         DEFAULT NULL COMMENT '入队时的标题快照',
  review_type        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=新上架 2=改名/改描述 3=到期重新上架 4=违规后二次申请 5=存量导入',
  revision           INT          NOT NULL DEFAULT 1 COMMENT '内容修订号：每次重新入队 +1',
  status             TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待审核 1=已审核（默认列表不再显示）',
  review_action      TINYINT      DEFAULT NULL COMMENT '1=已审核通过 2=已下架',
  queued_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本轮入队时间（列表倒序）',
  first_queued_at    DATETIME     DEFAULT NULL COMMENT '第一次进入审核页的时间',
  content_changed_at DATETIME     DEFAULT NULL COMMENT '最近一次改名/改描述时间',
  reviewer_id        BIGINT       DEFAULT NULL COMMENT '审核人；待审时表示当前认领人',
  claimed_at         DATETIME     DEFAULT NULL COMMENT '认领时间',
  reviewed_at        DATETIME     DEFAULT NULL COMMENT '处置时间',
  note               VARCHAR(255) DEFAULT NULL COMMENT '审核备注 / 下架原因',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_item (item_id),
  KEY idx_pending (status, queued_at),
  KEY idx_seller (seller_id, queued_at)
) ENGINE=InnoDB COMMENT='上架审核队列（运营逐条确认，保证每条商品都被看过）';

-- =============================================================
-- 初始化数据（可按需执行）
-- =============================================================
INSERT INTO school (id, name, city) VALUES (1, '示例大学', '示例市')
  ON DUPLICATE KEY UPDATE name = VALUES(name);
INSERT INTO campus (id, school_id, name, map_type) VALUES (1, 1, '主校区', 1)
  ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO item_category (id, parent_id, name, sort) VALUES
 (1,0,'教材书籍',1),(2,0,'数码电子',2),(3,0,'自行车/出行',3),(4,0,'宿舍生活',4),
 (5,0,'服饰鞋包',5),(6,0,'运动户外',6),(7,0,'美妆个护',7),(8,0,'乐器文创',8),
 (9,0,'票券卡类',9),(99,0,'其他',99)
  ON DUPLICATE KEY UPDATE name = VALUES(name);

-- status 默认 1（启用）、audit_status 默认 1（已通过），此处不显式写出；id 显式指定以保证脚本可重复执行
INSERT INTO trade_point (id, campus_id, name, source, point_type, coord_x, coord_y, available_period, safe_tags, safety_score, guide_text) VALUES
 (1,1,'图书馆东门广场',1,3,520,310,'["08:00-22:00"]','["监控","人流","照明","座椅"]',85,'从东门进入后直走约150米，广场旗杆旁'),
 (2,1,'一食堂正门',1,2,380,470,'["07:00-21:00"]','["人流","照明"]',80,'一食堂正门台阶处，紧邻超市'),
 (3,1,'3号宿舍楼下快递站',1,4,610,540,'["08:00-22:00"]','["监控","人流"]',78,'3号楼一层快递柜旁，有明显蓝色标识'),
 (4,1,'校警室门口',1,6,300,220,'["00:00-23:59"]','["监控","值守","照明"]',95,'南门内进校警室，24小时有人值守'),
 (5,1,'东门门卫室旁',1,1,700,300,'["06:00-23:00"]','["监控","值守","照明"]',90,'东门进校后左手边门卫室窗口前')
  ON DUPLICATE KEY UPDATE name = VALUES(name), safety_score = VALUES(safety_score);
