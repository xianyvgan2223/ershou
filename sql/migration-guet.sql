-- =============================================================
-- 把学校/校区数据从「示例大学」换成真实数据：桂林电子科技大学
-- 幂等，可重复执行
--
-- 坐标来源：高德 POI 检索（GCJ-02，与高德地图同一坐标系，可直接入库）
--   金鸡岭校区  110.336881, 25.283964   金鸡路1号
--   花江校区    110.416819, 25.311724   灵田镇
--   六合路校区  110.326284, 25.280997   六合路123号
--
-- 默认启用哪个校区：改最后一段 WHERE id = ? 的取值即可
--   1 = 金鸡岭校区（当前默认，老校区、面积小，与种子点位 800x600m 尺度接近）
--   2 = 花江校区（新主校区，面积大，建议把 campusSizeMeters 调到 1500）
--   3 = 六合路校区
-- =============================================================

-- 1) 学校改成真实名称（库里的 item/trade_point 都挂在校区 1 上，id 保持不变即可）
UPDATE school SET name = '桂林电子科技大学', city = '桂林市' WHERE id = 1;

-- 2) 补两个校区（id 显式指定，便于反复执行）
INSERT INTO campus (id, school_id, name, map_type, map_config, status) VALUES
 (2, 1, '花江校区',   2,
  JSON_OBJECT('center', JSON_OBJECT('lng', 110.416819, 'lat', 25.311724),
              'zoom', 15, 'pixelBounds', JSON_OBJECT('width', 800, 'height', 600),
              'campusSizeMeters', 1500, 'source', 'amap'),
  1),
 (3, 1, '六合路校区', 2,
  JSON_OBJECT('center', JSON_OBJECT('lng', 110.326284, 'lat', 25.280997),
              'zoom', 16, 'pixelBounds', JSON_OBJECT('width', 800, 'height', 600),
              'campusSizeMeters', 700, 'source', 'amap'),
  1)
ON DUPLICATE KEY UPDATE
  name = VALUES(name), map_type = VALUES(map_type), map_config = VALUES(map_config);

-- 3) 校区 1 改名并写入金鸡岭坐标
UPDATE campus
SET name = '金鸡岭校区',
    map_type = 2,
    map_config = JSON_OBJECT(
      'center', JSON_OBJECT('lng', 110.336881, 'lat', 25.283964),
      'zoom', 16,
      'pixelBounds', JSON_OBJECT('width', 800, 'height', 600),
      'campusSizeMeters', 800,
      'source', 'amap'
    )
WHERE id = 1;

-- 4) 校验
SELECT id, school_id, name, map_type, status, map_config FROM campus ORDER BY id;
SELECT id, name, city FROM school;
