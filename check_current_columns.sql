-- 检查当前数据库列名
USE diet_db;

-- 检查 city_sessions 表的列
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'city_sessions'
ORDER BY ORDINAL_POSITION;

-- 检查 activity_item 表的列
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'diet_db'
  AND TABLE_NAME = 'activity_item'
ORDER BY ORDINAL_POSITION;
