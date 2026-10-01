-- =============================================================
-- 地图容量压测 · 参数化造数存储过程
--
-- 用法：
--   CALL bench_map_seed(500,  10000);   -- 500 个点位 / 10000 件在售商品
--   CALL bench_map_seed(1000, 20000);
--   CALL bench_map_seed(5000, 100000);
-- 数据统一挂 campus_id=9（压测校区），id 从 900000 起，避免撞真实数据。
-- 清理用 sql/bench-map-cleanup.sql。
--
-- 注意：item 上有 AFTER INSERT 触发器，若造数报错请检查触发器与 id 区间。
-- =============================================================

DROP PROCEDURE IF EXISTS bench_map_seed;

DELIMITER $$
CREATE PROCEDURE bench_map_seed(IN p_points INT, IN p_items INT)
BEGIN
    DECLARE i INT DEFAULT 0;

    INSERT INTO campus (id, school_id, name, map_type, map_config, status)
    VALUES (9, 1, '压测校区', 2,
            JSON_OBJECT('center', JSON_OBJECT('lng', 110.416819, 'lat', 25.311724),
                        'zoom', 15, 'pixelBounds', JSON_OBJECT('width', 800, 'height', 600),
                        'campusSizeMeters', 1500, 'source', 'amap'),
            1)
    ON DUPLICATE KEY UPDATE name = VALUES(name);

    SET i = 0;
    WHILE i < p_points DO
        INSERT INTO trade_point
            (id, campus_id, name, source, point_type, lng, lat, safety_score, safe_tags, guide_text, status, audit_status)
        VALUES
            (900000 + i, 9, CONCAT('压测点-', i), 1, 3,
             110.400000 + (i % 50) * 0.0008,
             25.300000 + FLOOR(i / 50) * 0.0008,
             60 + (i % 40), '["监控"]', CONCAT('压测点位 ', i), 1, 1);
        SET i = i + 1;
    END WHILE;

    SET i = 0;
    WHILE i < p_items DO
        INSERT INTO item
            (id, seller_id, category_id, campus_id, title, description, price, original_price,
             condition_level, negotiable, is_unique, stock, status, prefer_point_id, images, published_at)
        VALUES
            (900000 + i, 2, 1, 9, CONCAT('压测商品 ', i), '压测数据',
             10.00 + (i % 500), 20.00, 2, 1, 1, 1, 2,
             900000 + (i % p_points), '[]', NOW());
        SET i = i + 1;
    END WHILE;

    SELECT p_points AS 造的点位数, p_items AS 造的商品数;
END$$
DELIMITER ;
