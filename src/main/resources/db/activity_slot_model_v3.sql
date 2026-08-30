-- CityFlow 九维槽位迁移（旧模型 -> v3）
-- 目标：mood -> experience_goal，scene -> companion，新增 feature，duration 只保留活动时长。
-- MySQL 8.x；可在已有 city_db 上执行。

USE city_db;

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_activity_slot_model_v3$$
CREATE PROCEDURE migrate_activity_slot_model_v3()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'activity_item' AND column_name = 'mood'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'activity_item' AND column_name = 'experience_goal'
    ) THEN
        ALTER TABLE activity_item CHANGE COLUMN mood experience_goal JSON NOT NULL;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'activity_item' AND column_name = 'scene'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'activity_item' AND column_name = 'companion'
    ) THEN
        ALTER TABLE activity_item CHANGE COLUMN scene companion JSON NOT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'activity_item' AND column_name = 'feature'
    ) THEN
        ALTER TABLE activity_item ADD COLUMN feature JSON NULL AFTER duration;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'activity_item' AND column_name = 'duration_minutes'
    ) THEN
        ALTER TABLE activity_item ADD COLUMN duration_minutes INT NULL AFTER feature;
    END IF;
END$$
CALL migrate_activity_slot_model_v3()$$
DROP PROCEDURE migrate_activity_slot_model_v3$$
DELIMITER ;

UPDATE activity_item SET feature = JSON_ARRAY() WHERE feature IS NULL;

-- location 中的便利性标签迁到 feature。
UPDATE activity_item
SET feature = CASE
        WHEN JSON_CONTAINS(feature, JSON_QUOTE('近地铁')) THEN feature
        ELSE JSON_ARRAY_APPEND(feature, '$', '近地铁')
    END
WHERE JSON_CONTAINS(location, JSON_QUOTE('近地铁'));

UPDATE activity_item
SET location = JSON_REMOVE(location, JSON_UNQUOTE(JSON_SEARCH(location, 'one', '近地铁')))
WHERE JSON_SEARCH(location, 'one', '近地铁') IS NOT NULL;

-- duration 中非时长属性全部迁到 feature。
UPDATE activity_item
SET feature = CASE
        WHEN JSON_CONTAINS(feature, JSON_QUOTE('室内')) THEN feature
        ELSE JSON_ARRAY_APPEND(feature, '$', '室内')
    END
WHERE JSON_CONTAINS(duration, JSON_QUOTE('室内'));
UPDATE activity_item SET duration = JSON_REMOVE(duration, JSON_UNQUOTE(JSON_SEARCH(duration, 'one', '室内')))
WHERE JSON_SEARCH(duration, 'one', '室内') IS NOT NULL;

UPDATE activity_item
SET feature = CASE
        WHEN JSON_CONTAINS(feature, JSON_QUOTE('户外')) THEN feature
        ELSE JSON_ARRAY_APPEND(feature, '$', '户外')
    END
WHERE JSON_CONTAINS(duration, JSON_QUOTE('户外'));
UPDATE activity_item SET duration = JSON_REMOVE(duration, JSON_UNQUOTE(JSON_SEARCH(duration, 'one', '户外')))
WHERE JSON_SEARCH(duration, 'one', '户外') IS NOT NULL;

UPDATE activity_item
SET feature = CASE
        WHEN JSON_CONTAINS(feature, JSON_QUOTE('近距离')) THEN feature
        ELSE JSON_ARRAY_APPEND(feature, '$', '近距离')
    END
WHERE JSON_CONTAINS(duration, JSON_QUOTE('近距离'));
UPDATE activity_item SET duration = JSON_REMOVE(duration, JSON_UNQUOTE(JSON_SEARCH(duration, 'one', '近距离')))
WHERE JSON_SEARCH(duration, 'one', '近距离') IS NOT NULL;

UPDATE activity_item
SET feature = CASE
        WHEN JSON_CONTAINS(feature, JSON_QUOTE('少排队')) THEN feature
        ELSE JSON_ARRAY_APPEND(feature, '$', '少排队')
    END
WHERE JSON_CONTAINS(duration, JSON_QUOTE('少排队'));
UPDATE activity_item SET duration = JSON_REMOVE(duration, JSON_UNQUOTE(JSON_SEARCH(duration, 'one', '少排队')))
WHERE JSON_SEARCH(duration, 'one', '少排队') IS NOT NULL;

UPDATE activity_item
SET feature = CASE
        WHEN JSON_CONTAINS(feature, JSON_QUOTE('交通方便')) THEN feature
        ELSE JSON_ARRAY_APPEND(feature, '$', '交通方便')
    END
WHERE JSON_CONTAINS(duration, JSON_QUOTE('交通方便'));
UPDATE activity_item SET duration = JSON_REMOVE(duration, JSON_UNQUOTE(JSON_SEARCH(duration, 'one', '交通方便')))
WHERE JSON_SEARCH(duration, 'one', '交通方便') IS NOT NULL;

-- 旧时长标签给一个规划可用的软回填；后续活动维护应优先填写真实 duration_minutes。
UPDATE activity_item SET duration_minutes = 240
WHERE duration_minutes IS NULL AND JSON_CONTAINS(duration, JSON_QUOTE('半天'));
UPDATE activity_item SET duration_minutes = 480
WHERE duration_minutes IS NULL AND JSON_CONTAINS(duration, JSON_QUOTE('全天'));

ALTER TABLE activity_item MODIFY COLUMN feature JSON NOT NULL;

-- 标准槽位字典迁移。
UPDATE city_slot_option SET slot_name = 'experienceGoal' WHERE slot_name = 'mood';
UPDATE city_slot_option SET slot_name = 'companion' WHERE slot_name = 'scene';
UPDATE city_slot_option SET slot_name = 'feature'
WHERE slot_name = 'location' AND option_value = '近地铁';
UPDATE city_slot_option SET slot_name = 'feature'
WHERE slot_name = 'duration' AND option_value IN ('室内', '户外', '室外', '近距离', '少排队', '交通方便');

INSERT INTO city_slot_option(slot_name, option_value, sort_order, enabled, created_at, updated_at)
SELECT 'duration', '1小时内', 10, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM city_slot_option WHERE slot_name='duration' AND option_value='1小时内');
INSERT INTO city_slot_option(slot_name, option_value, sort_order, enabled, created_at, updated_at)
SELECT 'duration', '1-2小时', 20, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM city_slot_option WHERE slot_name='duration' AND option_value='1-2小时');
INSERT INTO city_slot_option(slot_name, option_value, sort_order, enabled, created_at, updated_at)
SELECT 'duration', '2-4小时', 30, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM city_slot_option WHERE slot_name='duration' AND option_value='2-4小时');
INSERT INTO city_slot_option(slot_name, option_value, sort_order, enabled, created_at, updated_at)
SELECT 'feature', '户外', 20, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM city_slot_option WHERE slot_name='feature' AND option_value='户外');
