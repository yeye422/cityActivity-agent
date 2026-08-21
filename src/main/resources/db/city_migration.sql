-- City-Agent 数据表迁移。
-- 使用方式：先执行 diet_db.sql，再执行本脚本，再执行 city_seed.sql。
-- 仅重命名核心业务表，会话、Trace、反馈表保持原结构以兼容现有评估链路。

SET NAMES utf8mb4;

RENAME TABLE
    diet_slot_option TO city_slot_option,
    meal_item TO activity_item;

ALTER TABLE activity_item
    RENAME COLUMN meal_time TO activity_time,
    RENAME COLUMN health_goal TO budget,
    RENAME COLUMN cuisine TO activity_type,
    RENAME COLUMN taste TO style,
    RENAME COLUMN convenience TO duration;

ALTER TABLE activity_item
    ADD COLUMN city JSON NULL AFTER name,
    ADD COLUMN location JSON NULL AFTER city;

UPDATE activity_item
SET city = JSON_ARRAY('西安'),
    location = JSON_ARRAY('近地铁')
WHERE city IS NULL
   OR location IS NULL;

ALTER TABLE activity_item
    MODIFY COLUMN city JSON NOT NULL,
    MODIFY COLUMN location JSON NOT NULL;
