-- ==========================================
-- 数据库表重命名脚本: diet_* → city_*
-- ==========================================
-- 执行前必须备份: mysqldump diet_db > diet_db_backup_$(date +%Y%m%d_%H%M%S).sql
-- 执行时间: 预计1-2分钟

USE diet_db;

START TRANSACTION;

-- ==========================================
-- 第一步: 重命名所有表
-- ==========================================

ALTER TABLE `diet_sessions` RENAME TO `city_sessions`;
ALTER TABLE `diet_messages` RENAME TO `city_messages`;
ALTER TABLE `diet_slot_option` RENAME TO `city_slot_option`;
ALTER TABLE `diet_agent_trace` RENAME TO `city_agent_trace`;
ALTER TABLE `diet_feedback` RENAME TO `city_feedback`;

-- ==========================================
-- 第二步: 更新外键约束（如果有）
-- ==========================================

-- 更新 city_messages 表的外键（如果存在）
-- ALTER TABLE `city_messages` DROP FOREIGN KEY `fk_diet_messages_sessions`;
-- ALTER TABLE `city_messages` ADD CONSTRAINT `fk_city_messages_sessions`
--     FOREIGN KEY (`session_id`) REFERENCES `city_sessions` (`id`) ON DELETE CASCADE;

-- ==========================================
-- 第三步: 验证表结构
-- ==========================================

-- 验证所有表已重命名
SELECT
    '验证: 表已重命名' AS check_name,
    COUNT(*) AS renamed_table_count
FROM information_schema.tables
WHERE table_schema = 'diet_db'
  AND table_name LIKE 'city_%';

-- 显示所有表
SHOW TABLES;

-- 如果验证通过，提交事务
-- COMMIT;

-- 如果验证失败，回滚事务
-- ROLLBACK;

-- ==========================================
-- 手动检查点
-- ==========================================
-- 执行到这里后，检查上面的验证结果
-- 应该有 5 个 city_* 表
--
-- 确认无误后，手动执行: COMMIT;
-- 如果有问题，执行: ROLLBACK;
-- ==========================================
