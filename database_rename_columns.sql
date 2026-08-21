-- ==========================================
-- 数据库列重命名脚本
-- ==========================================
-- 执行前必须备份: mysqldump diet_db > diet_db_backup_$(date +%Y%m%d_%H%M%S).sql

USE diet_db;

START TRANSACTION;

-- ==========================================
-- 第一步: 重命名 city_sessions 表的列
-- ==========================================

-- 检查列是否存在
SELECT COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'city_sessions'
  AND COLUMN_NAME IN ('last_recommendations', 'last_recommended_activity_ids');

-- last_recommendations → last_recommended_activity_ids
ALTER TABLE `city_sessions`
CHANGE COLUMN `last_recommendations` `last_recommended_activity_ids` JSON COMMENT '上次推荐的活动ID列表';

-- ==========================================
-- 第二步: 重命名 activity_item 表的列（如果还没改）
-- ==========================================

-- 检查 activity_item 表的列
SELECT COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'activity_item'
  AND COLUMN_NAME IN ('meal_time', 'activity_time', 'health_goal', 'budget', 'cuisine', 'activity_type', 'taste', 'style', 'convenience', 'duration');

-- 如果列还是旧名称，执行以下重命名
-- meal_time → activity_time
-- ALTER TABLE `activity_item` CHANGE COLUMN `meal_time` `activity_time` JSON NOT NULL COMMENT '活动时间';

-- health_goal → budget
-- ALTER TABLE `activity_item` CHANGE COLUMN `health_goal` `budget` JSON NOT NULL COMMENT '预算';

-- cuisine → activity_type
-- ALTER TABLE `activity_item` CHANGE COLUMN `cuisine` `activity_type` JSON NOT NULL COMMENT '活动类型';

-- taste → style
-- ALTER TABLE `activity_item` CHANGE COLUMN `taste` `style` JSON NOT NULL COMMENT '活动风格';

-- convenience → duration
-- ALTER TABLE `activity_item` CHANGE COLUMN `convenience` `duration` JSON NOT NULL COMMENT '活动时长';

-- ==========================================
-- 验证
-- ==========================================

-- 验证 city_sessions 列名
SELECT 'city_sessions 列验证' AS check_type,
       COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'city_sessions'
ORDER BY ORDINAL_POSITION;

-- 验证 activity_item 列名
SELECT 'activity_item 列验证' AS check_type,
       COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'activity_item'
ORDER BY ORDINAL_POSITION;

-- 如果验证通过，提交事务
-- COMMIT;

-- 如果验证失败，回滚事务
-- ROLLBACK;

-- ==========================================
-- 手动检查点
-- ==========================================
-- 执行到这里后，请检查上面的验证结果
-- 确认无误后，手动执行: COMMIT;
-- 如果有问题，执行: ROLLBACK;
-- ==========================================
