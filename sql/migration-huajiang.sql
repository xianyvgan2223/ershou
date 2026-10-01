-- =============================================================
-- 切换到「花江校区」，并把种子面交点替换为花江校区的真实地点
-- 幂等，可重复执行
--
-- 地点坐标来源：高德 POI 检索（GCJ-02，可直接入库）
--   图书馆          110.418835, 25.313315
--   中央食堂        110.417475, 25.312275
--   菜鸟驿站        110.416495, 25.316311
--   中央食堂快递站  110.416375, 25.313075
--   雅园餐厅        110.414401, 25.308052
--   教工餐厅        110.416275, 25.316325
--   仲园餐厅        110.410575, 25.311375
--   南门            110.419324, 25.304553
--   南二门          110.416719, 25.304731
--   西门            110.409931, 25.304219
--   清苑(宿舍)      110.415012, 25.319317
--   泾苑(宿舍)      110.414713, 25.317604
-- =============================================================

-- 1) 商品与点位都迁到花江校区（campus.id=2）
UPDATE item        SET campus_id = 2 WHERE campus_id <> 2;
UPDATE trade_point SET campus_id = 2 WHERE campus_id <> 2;

-- 2) 校名、校区名与底图中心点校正
UPDATE school SET name = '桂林电子科技大学', city = '桂林市' WHERE id = 1;
UPDATE campus SET name = '花江校区' WHERE id = 2;
UPDATE campus
SET map_config = JSON_OBJECT(
      'center', JSON_OBJECT('lng', 110.416819, 'lat', 25.311724),
      'zoom', 15,
      'pixelBounds', JSON_OBJECT('width', 800, 'height', 600),
      'campusSizeMeters', 1500,
      'source', 'amap')
WHERE id = 2;

-- 3) 种子点位换成花江校区真实地点（写入 lng/lat 后前端优先用真实坐标）
UPDATE trade_point SET
  name = '花江校区图书馆',
  point_type = 5,
  guide_text = '图书馆正门台阶处，进馆右侧有座椅',
  safety_score = 88,
  safe_tags = '["监控","人流","照明"]',
  lng = 110.418835, lat = 25.313315
WHERE id = 1;

UPDATE trade_point SET
  name = '中央食堂正门',
  point_type = 2,
  guide_text = '中央食堂正门台阶处，紧邻超市',
  safety_score = 82,
  safe_tags = '["人流","照明"]',
  lng = 110.417475, lat = 25.312275
WHERE id = 2;

UPDATE trade_point SET
  name = '菜鸟驿站(中央食堂旁)',
  point_type = 4,
  guide_text = '中央食堂旁菜鸟驿站，取件高峰 12:00-13:30',
  safety_score = 80,
  safe_tags = '["监控","人流"]',
  lng = 110.416495, lat = 25.316311
WHERE id = 3;

UPDATE trade_point SET
  name = '花江校区南门',
  point_type = 1,
  guide_text = '南门门卫室窗口前，24 小时有人值守',
  safety_score = 94,
  safe_tags = '["监控","值守","照明"]',
  lng = 110.419324, lat = 25.304553
WHERE id = 4;

UPDATE trade_point SET
  name = '南二门门卫室旁',
  point_type = 1,
  guide_text = '南二门进校后左手边门卫室前',
  safety_score = 90,
  safe_tags = '["监控","值守","照明"]',
  lng = 110.416719, lat = 25.304731
WHERE id = 5;

-- 4) 补充几个官方点位（有真实坐标后才谈得上"安全点位"）
INSERT INTO trade_point
  (id, campus_id, name, source, point_type, lng, lat, available_period, safe_tags, safety_score, guide_text, status, audit_status)
VALUES
 (6, 2, '中央食堂快递站', 1, 4, 110.416375, 25.313075, '["08:00-22:00"]', '["监控","人流"]',                  80, '计算机与信息安全学院西南侧，蓝色快递柜旁', 1, 1),
 (7, 2, '雅园餐厅门口',   1, 2, 110.414401, 25.308052, '["07:00-21:00"]', '["人流","照明"]',                  75, '雅园餐厅正门，饭点人流大',                 1, 1),
 (8, 2, '清苑宿舍区',     1, 7, 110.415012, 25.319317, '["00:00-23:59"]', '["监控","照明"]',                  72, '清苑宿舍区入口门厅，仅建议白天面交',       1, 1)
ON DUPLICATE KEY UPDATE
  campus_id = VALUES(campus_id), name = VALUES(name), lng = VALUES(lng), lat = VALUES(lat),
  guide_text = VALUES(guide_text), safety_score = VALUES(safety_score), safe_tags = VALUES(safe_tags);

-- 5) 校验
SELECT '--- 校区 ---' AS info;
SELECT id, name, map_type, map_config FROM campus WHERE id = 2;
SELECT '--- 面交点（应有真实经纬度） ---' AS info;
SELECT id, campus_id, name, coord_x, coord_y, lng, lat FROM trade_point ORDER BY id;
SELECT '--- 商品归属 ---' AS info;
SELECT campus_id, COUNT(*) AS 商品数 FROM item GROUP BY campus_id;
