-- =============================================================
-- 高德地图接入：把主校区切成第三方地图，并给出现有面交点的换算基准
-- 幂等，可重复执行
--
-- 坐标说明（重要）：
--   高德用的是 GCJ-02（火星坐标），GPS / 浏览器定位给的是 WGS-84，
--   两者在国内差 50~200 米。本脚本把 map_config.center 当作换算基准，
--   前端按「像素 → 经纬度」等比映射显示老数据；要让点位精确，
--   请用高德坐标拾取器 https://developer.amap.com/tools/picker
--   逐个把真实 GCJ-02 经纬度写进 trade_point.lng / lat（写入后优先使用真实值）。
-- =============================================================

-- 1) 主校区切到第三方地图（map_type: 1=自绘平面图 2=第三方地图）
UPDATE campus SET map_type = 2 WHERE id = 1;

-- 2) 校区底图配置：中心点 + 缩放 + 像素基准 + 假定的校区边长
--    ⚠️ center 默认填的是北京天安门，请改成你校区的真实 GCJ-02 坐标！
UPDATE campus
SET map_config = JSON_OBJECT(
  'center', JSON_OBJECT('lng', 116.397428, 'lat', 39.909230),
  'zoom', 16,
  'pixelBounds', JSON_OBJECT('width', 800, 'height', 600),
  'campusSizeMeters', 800,
  'source', 'amap'
)
WHERE id = 1;

-- 3) 面交点经纬度回填模板（真实坐标采集后执行，示例已注释）
-- UPDATE trade_point SET lng = 116.397428, lat = 39.909230 WHERE id = 1;
-- UPDATE trade_point SET lng = ?, lat = ? WHERE id = 2;

-- 4) 校验
SELECT id, name, map_type, map_config FROM campus WHERE id = 1;
SELECT id, name, coord_x, coord_y, lng, lat FROM trade_point WHERE campus_id = 1;
