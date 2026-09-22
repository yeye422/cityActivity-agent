-- 仅用于回滚 agent_memory_migration.sql。
-- 执行前先导出 city_preference_fact；本操作会删除长期偏好数据。
USE city_db;

DROP TABLE IF EXISTS city_preference_fact;
