-- ==========================================
-- 城市活动助手 - 数据库字段重构脚本
-- ==========================================
-- 执行前必须备份: mysqldump diet_db > diet_db_backup_$(date +%Y%m%d_%H%M%S).sql
-- 执行时间: 预计5-10分钟（取决于数据量）
-- 影响: 表名、列名、JSON键名

USE diet_db;

-- 开始事务
START TRANSACTION;

-- ==========================================
-- 第一步: 重命名主表 meal_item → activity_item
-- ==========================================
ALTER TABLE `meal_item` RENAME TO `activity_item`;

-- ==========================================
-- 第二步: 重命名 activity_item 表的列
-- ==========================================

-- 2.1 添加新列 activity_time
ALTER TABLE `activity_item`
ADD COLUMN `activity_time` JSON NOT NULL COMMENT '活动时间' AFTER `name`;

-- 2.2 复制数据 meal_time → activity_time
UPDATE `activity_item`
SET `activity_time` = `meal_time`;

-- 2.3 删除旧列 meal_time
ALTER TABLE `activity_item`
DROP COLUMN `meal_time`;

-- 2.4 添加新列 budget
ALTER TABLE `activity_item`
ADD COLUMN `budget` JSON NOT NULL COMMENT '预算' AFTER `scene`;

-- 2.5 复制数据 health_goal → budget
UPDATE `activity_item`
SET `budget` = `health_goal`;

-- 2.6 删除旧列 health_goal
ALTER TABLE `activity_item`
DROP COLUMN `health_goal`;

-- 2.7 添加新列 activity_type
ALTER TABLE `activity_item`
ADD COLUMN `activity_type` JSON NOT NULL COMMENT '活动类型' AFTER `budget`;

-- 2.8 复制数据 cuisine → activity_type
UPDATE `activity_item`
SET `activity_type` = `cuisine`;

-- 2.9 删除旧列 cuisine
ALTER TABLE `activity_item`
DROP COLUMN `cuisine`;

-- ==========================================
-- 第三步: 更新 diet_sessions 表的 JSON 槽位
-- ==========================================

-- 更新 slots JSON 字段的键名
UPDATE `diet_sessions`
SET `slots` = JSON_REMOVE(
    JSON_SET(
        JSON_SET(
            JSON_SET(
                `slots`,
                '$.activityTime', COALESCE(JSON_EXTRACT(`slots`, '$.mealTime'), JSON_ARRAY())
            ),
            '$.budget', COALESCE(JSON_EXTRACT(`slots`, '$.healthGoal'), JSON_ARRAY())
        ),
        '$.activityType', COALESCE(JSON_EXTRACT(`slots`, '$.cuisine'), JSON_ARRAY())
    ),
    '$.mealTime', '$.healthGoal', '$.cuisine'
)
WHERE `slots` IS NOT NULL
  AND JSON_TYPE(`slots`) = 'OBJECT';

-- ==========================================
-- 第四步: 更新 diet_slot_option 表的槽位名称
-- ==========================================

UPDATE `diet_slot_option` SET `slot_name` = 'activityTime' WHERE `slot_name` = 'mealTime';
UPDATE `diet_slot_option` SET `slot_name` = 'budget' WHERE `slot_name` = 'healthGoal';
UPDATE `diet_slot_option` SET `slot_name` = 'activityType' WHERE `slot_name` = 'cuisine';

-- ==========================================
-- 第五步: 重建索引
-- ==========================================

-- 删除旧索引（如果存在）
ALTER TABLE `activity_item` DROP INDEX IF EXISTS `idx_public_meal_source`;
ALTER TABLE `activity_item` DROP INDEX IF EXISTS `idx_private_meal_source`;

-- 创建新索引
CREATE INDEX `idx_public_activity_source` ON `activity_item`(`source_type`) WHERE `source_type` = 'PUBLIC';
CREATE INDEX `idx_private_activity_source` ON `activity_item`(`owner_user_id`, `source_type`) WHERE `source_type` = 'PERSONAL';

-- ==========================================
-- 验证数据完整性
-- ==========================================

-- 验证表结构
SELECT
    '验证: activity_item 表结构' AS check_name,
    COUNT(*) AS total_rows,
    SUM(CASE WHEN activity_time IS NOT NULL THEN 1 ELSE 0 END) AS has_activity_time,
    SUM(CASE WHEN budget IS NOT NULL THEN 1 ELSE 0 END) AS has_budget,
    SUM(CASE WHEN activity_type IS NOT NULL THEN 1 ELSE 0 END) AS has_activity_type
FROM `activity_item`;

-- 验证槽位选项
SELECT
    '验证: diet_slot_option 槽位名称' AS check_name,
    slot_name,
    COUNT(*) AS count
FROM `diet_slot_option`
WHERE slot_name IN ('activityTime', 'budget', 'activityType', 'mood', 'scene', 'taste', 'convenience')
GROUP BY slot_name;

-- 验证会话槽位
SELECT
    '验证: diet_sessions slots 字段' AS check_name,
    COUNT(*) AS total_sessions,
    SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.activityTime') THEN 1 ELSE 0 END) AS has_activityTime,
    SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.budget') THEN 1 ELSE 0 END) AS has_budget,
    SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.activityType') THEN 1 ELSE 0 END) AS has_activityType,
    SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.mealTime') THEN 1 ELSE 0 END) AS old_mealTime_count
FROM `diet_sessions`
WHERE slots IS NOT NULL;

-- 如果验证通过，提交事务
-- COMMIT;

-- 如果验证失败，回滚事务
-- ROLLBACK;

-- ==========================================
-- 手动检查点
-- ==========================================
-- 执行到这里后，请检查上面的验证结果
-- 如果一切正常：
--   1. old_mealTime_count 应该为 0
--   2. activity_time, budget, activity_type 列都有数据
--   3. 槽位名称已更新
--
-- 确认无误后，手动执行: COMMIT;
-- 如果有问题，执行: ROLLBACK;
-- ==========================================
