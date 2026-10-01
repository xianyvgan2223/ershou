-- 地图容量压测 · 清理（幂等，可重复执行）
DELETE FROM item        WHERE campus_id = 9;
DELETE FROM trade_point WHERE campus_id = 9;
DELETE FROM campus      WHERE id = 9;
SELECT '剩余压测商品' AS info, COUNT(*) AS n FROM item WHERE campus_id = 9
UNION ALL
SELECT '剩余压测点位', COUNT(*) FROM trade_point WHERE campus_id = 9;
