-- 数据库字段重构脚本
-- 将所有 meal 相关字段改为 activity 相关
-- 执行前请备份数据库: mysqldump diet_db > diet_db_backup_$(date +%Y%m%d).sql

USE diet_db;

-- ==========================================
-- 第一步: 重命名表
-- ==========================================

-- 1. meal_item → activity_item
ALTER TABLE `meal_item` RENAME TO `activity_item`;

-- ==========================================
-- 第二步: 修改 activity_item 表的 JSON 列键名
-- ==========================================

-- 注意: MySQL JSON 列不支持直接重命名键，需要更新每一行数据
-- 这个操作可能耗时较长，建议在低峰期执行

-- 2.1 meal_time → activity_time
UPDATE `activity_item`
SET `meal_time` = JSON_OBJECT('activity_time', JSON_EXTRACT(`meal_time`, '$'));

-- 实际上 MySQL 不支持列名包含在 JSON 值中，我们需要先添加新列
ALTER TABLE `activity_item`
ADD COLUMN `activity_time` JSON NOT NULL AFTER `name`;

UPDATE `activity_item`
SET `activity_time` = `meal_time`;

ALTER TABLE `activity_item`
DROP COLUMN `meal_time`;

-- 2.2 health_goal → budget
ALTER TABLE `activity_item`
ADD COLUMN `budget` JSON NOT NULL AFTER `scene`;

UPDATE `activity_item`
SET `budget` = `health_goal`;

ALTER TABLE `activity_item`
DROP COLUMN `health_goal`;

-- 2.3 cuisine → activity_type
ALTER TABLE `activity_item`
ADD COLUMN `activity_type` JSON NOT NULL AFTER `budget`;

UPDATE `activity_item`
SET `activity_type` = `cuisine`;

ALTER TABLE `activity_item`
DROP COLUMN `cuisine`;

-- ==========================================
-- 第三步: 修改 diet_sessions 表的 JSON 槽位字段
-- ==========================================

-- diet_sessions.slots 是 JSON 类型，包含 {mealTime: [], healthGoal: [], cuisine: []}
-- 需要更新每一行的 JSON 键名

UPDATE `diet_sessions`
SET `slots` = JSON_SET(
    JSON_REMOVE(`slots`, '$.mealTime', '$.healthGoal', '$.cuisine'),
    '$.activityTime', JSON_EXTRACT(`slots`, '$.mealTime'),
    '$.budget', JSON_EXTRACT(`slots`, '$.healthGoal'),
    '$.activityType', JSON_EXTRACT(`slots`, '$.cuisine')
)
WHERE `slots` IS NOT NULL;

-- ==========================================
-- 第四步: 更新索引
-- ==========================================

-- 删除旧索引
ALTER TABLE `activity_item` DROP INDEX IF EXISTS `idx_public_meal_source`;
ALTER TABLE `activity_item` DROP INDEX IF EXISTS `idx_private_meal_source`;

-- 创建新索引
CREATE INDEX `idx_public_activity_source` ON `activity_item`(`source_type` ASC) USING BTREE;
CREATE INDEX `idx_private_activity_source` ON `activity_item`(`owner_user_id` ASC, `source_type` ASC) USING BTREE;

-- ==========================================
-- 第五步: 更新 diet_slot_option 表的槽位名称
-- ==========================================

UPDATE `diet_slot_option` SET `slot_name` = 'activityTime' WHERE `slot_name` = 'mealTime';
UPDATE `diet_slot_option` SET `slot_name` = 'budget' WHERE `slot_name` = 'healthGoal';
UPDATE `diet_slot_option` SET `slot_name` = 'activityType' WHERE `slot_name` = 'cuisine';

-- ==========================================
-- 验证
-- ==========================================

-- 验证表结构
SHOW CREATE TABLE `activity_item`;

-- 验证数据
SELECT id, name, activity_time, budget, activity_type FROM `activity_item` LIMIT 5;

-- 验证槽位选项
SELECT DISTINCT slot_name FROM `diet_slot_option`;

-- 验证会话槽位
SELECT slots FROM `diet_sessions` LIMIT 5;

COMMIT;
