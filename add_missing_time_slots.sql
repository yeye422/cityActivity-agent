-- 添加缺失的时间槽位选项
-- 执行前: USE diet_db;

INSERT INTO `diet_slot_option` (slot_name, slot_value, sort_order, is_active, created_at, updated_at) VALUES
('mealTime', '周六', 85, 1, NOW(), NOW()),
('mealTime', '周日上午', 5, 1, NOW(), NOW()),
('mealTime', '周日下午', 15, 1, NOW(), NOW()),
('mealTime', '周日晚上', 25, 1, NOW(), NOW());

-- 验证
SELECT slot_value FROM diet_slot_option WHERE slot_name = 'mealTime' ORDER BY sort_order;
