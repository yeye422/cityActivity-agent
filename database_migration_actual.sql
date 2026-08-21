-- ==========================================
-- 数据库迁移脚本（基于实际表结构）
-- ==========================================
-- 当前表状态：
-- ✅ city_messages - 已重命名
-- ✅ city_sessions - 已重命名
-- ✅ city_slot_option - 已重命名
-- ⏳ activity_item - 列名需要更新
-- ⏳ diet_request_trace - 需要重命名为 city_request_trace
-- ⏳ city_activity - 旧表，可能需要删除或重命名
-- ⏳ recommend_feedback - 需要重命名为 city_feedback

USE diet_db;

START TRANSACTION;

-- ==========================================
-- 第一步: 处理遗留的旧表
-- ==========================================

-- 1.1 检查 city_activity 是否是旧表（如果是，删除或备份）
-- 如果这是旧的餐食表，建议删除
-- DROP TABLE IF EXISTS `city_activity`;

-- ==========================================
-- 第二步: 重命名剩余的表
-- ==========================================

-- 2.1 diet_request_trace → city_request_trace
ALTER TABLE `diet_request_trace` RENAME TO `city_request_trace`;

-- 2.2 recommend_feedback → city_feedback
ALTER TABLE `recommend_feedback` RENAME TO `city_feedback`;

-- ==========================================
-- 第三步: 更新 activity_item 表的列名
-- ==========================================

-- 检查 activity_item 当前列结构
SHOW COLUMNS FROM `activity_item`;

-- 3.1 如果列名还是旧的，需要重命名
-- 注意：请先检查列是否存在，避免重复执行

-- 如果存在 meal_time 列，重命名为 activity_time
-- ALTER TABLE `activity_item` CHANGE COLUMN `meal_time` `activity_time` JSON NOT NULL COMMENT '活动时间';

-- 如果存在 health_goal 列，重命名为 budget
-- ALTER TABLE `activity_item` CHANGE COLUMN `health_goal` `budget` JSON NOT NULL COMMENT '预算';

-- 如果存在 cuisine 列，重命名为 activity_type
-- ALTER TABLE `activity_item` CHANGE COLUMN `cuisine` `activity_type` JSON NOT NULL COMMENT '活动类型';

-- ==========================================
-- 第四步: 更新 city_sessions 表的 JSON 字段
-- ==========================================

-- 检查 city_sessions 的列结构
SHOW COLUMNS FROM `city_sessions`;

-- 如果 last_recommendations 列还存在，需要重命名
-- ALTER TABLE `city_sessions` CHANGE COLUMN `last_recommendations` `last_recommended_activity_ids` JSON COMMENT '上次推荐的活动ID列表';

-- 更新 slots JSON 字段的键名（如果还是旧的键名）
UPDATE `city_sessions`
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
  AND JSON_TYPE(`slots`) = 'OBJECT'
  AND (
      JSON_CONTAINS_PATH(`slots`, 'one', '$.mealTime')
      OR JSON_CONTAINS_PATH(`slots`, 'one', '$.healthGoal')
      OR JSON_CONTAINS_PATH(`slots`, 'one', '$.cuisine')
  );

-- ==========================================
-- 第五步: 更新 city_slot_option 表的槽位名称
-- ==========================================

UPDATE `city_slot_option` SET `slot_name` = 'activityTime' WHERE `slot_name` = 'mealTime';
UPDATE `city_slot_option` SET `slot_name` = 'budget' WHERE `slot_name` = 'healthGoal';
UPDATE `city_slot_option` SET `slot_name` = 'activityType' WHERE `slot_name` = 'cuisine';

-- ==========================================
-- 验证
-- ==========================================

-- 验证表名
SELECT '表名验证' AS check_type, TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'diet_db'
ORDER BY TABLE_NAME;

-- 验证 activity_item 列名
SELECT '活动表列名' AS check_type, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'activity_item'
ORDER BY ORDINAL_POSITION;

-- 验证槽位选项
SELECT 'city_slot_option 槽位名称' AS check_type, slot_name, COUNT(*) AS count
FROM `city_slot_option`
GROUP BY slot_name;

-- 如果验证通过，提交事务
-- COMMIT;

-- 如果验证失败，回滚事务
-- ROLLBACK;

-- ==========================================
-- 手动检查点
-- ==========================================
-- 执行到这里后，请检查上面的验证结果
--
-- 确认无误后，手动执行: COMMIT;
-- 如果有问题，执行: ROLLBACK;
-- ==========================================
