-- ==========================================
-- 槽位重命名脚本: taste→style, convenience→duration（方案A）
-- ==========================================
-- 执行前必须备份: mysqldump diet_db > diet_db_backup_$(date +%Y%m%d_%H%M%S).sql

USE diet_db;

START TRANSACTION;

-- ==========================================
-- 第一步: 更新 city_slot_option 表的槽位名称
-- ==========================================

-- taste → style
UPDATE `city_slot_option`
SET `slot_name` = 'style'
WHERE `slot_name` = 'taste';

-- convenience → duration
UPDATE `city_slot_option`
SET `slot_name` = 'duration'
WHERE `slot_name` = 'convenience';

-- ==========================================
-- 第二步: 更新 city_sessions 表的 slots JSON 字段
-- ==========================================

-- 更新所有会话的 slots JSON 键名
UPDATE `city_sessions`
SET `slots` = JSON_REMOVE(
    JSON_SET(
        JSON_SET(
            `slots`,
            '$.style', COALESCE(JSON_EXTRACT(`slots`, '$.taste'), JSON_ARRAY())
        ),
        '$.duration', COALESCE(JSON_EXTRACT(`slots`, '$.convenience'), JSON_ARRAY())
    ),
    '$.taste', '$.convenience'
)
WHERE `slots` IS NOT NULL
  AND JSON_TYPE(`slots`) = 'OBJECT'
  AND (
      JSON_CONTAINS_PATH(`slots`, 'one', '$.taste')
      OR JSON_CONTAINS_PATH(`slots`, 'one', '$.convenience')
  );

-- ==========================================
-- 第三步: 更新 activity_item 表的列名
-- ==========================================

-- 检查列是否存在
SELECT COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'activity_item'
  AND COLUMN_NAME IN ('taste', 'convenience');

-- taste → style
ALTER TABLE `activity_item`
CHANGE COLUMN `taste` `style` JSON NOT NULL COMMENT '活动风格';

-- convenience → duration
ALTER TABLE `activity_item`
CHANGE COLUMN `convenience` `duration` JSON NOT NULL COMMENT '活动时长';

-- ==========================================
-- 验证
-- ==========================================

-- 验证 city_slot_option 槽位名称
SELECT 'city_slot_option 槽位验证' AS check_type,
       slot_name,
       COUNT(*) AS count
FROM `city_slot_option`
GROUP BY slot_name
ORDER BY slot_name;

-- 验证 activity_item 列名
SELECT 'activity_item 列验证' AS check_type,
       COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'activity_item'
  AND COLUMN_NAME IN ('location', 'duration', 'taste', 'convenience');

-- 验证 city_sessions 的 slots JSON 键名（抽样检查）
SELECT 'city_sessions slots 验证' AS check_type,
       id,
       JSON_KEYS(slots) AS slot_keys
FROM `city_sessions`
WHERE `slots` IS NOT NULL
LIMIT 5;

-- 统计验证
SELECT 'city_sessions slots 统计' AS check_type,
       SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.location') THEN 1 ELSE 0 END) AS has_location,
       SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.duration') THEN 1 ELSE 0 END) AS has_duration,
       SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.taste') THEN 1 ELSE 0 END) AS old_taste_count,
       SUM(CASE WHEN JSON_CONTAINS_PATH(slots, 'one', '$.convenience') THEN 1 ELSE 0 END) AS old_convenience_count
FROM `city_sessions`
WHERE slots IS NOT NULL;

-- 如果验证通过，提交事务
-- COMMIT;

-- 如果验证失败，回滚事务
-- ROLLBACK;

-- ==========================================
-- 手动检查点
-- ==========================================
-- 执行到这里后，请检查上面的验证结果
-- 期望结果：
--   1. city_slot_option 应该有 location 和 duration 槽位
--   2. activity_item 应该有 location 和 duration 列
--   3. old_taste_count 和 old_convenience_count 应该为 0
--
-- 确认无误后，手动执行: COMMIT;
-- 如果有问题，执行: ROLLBACK;
-- ==========================================
