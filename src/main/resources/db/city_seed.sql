-- City-Agent 城市活动演示数据（九维槽位模型）。
-- 先执行 database_init_final.sql 和 evaluation_loop_migration.sql，再执行本脚本。

SET NAMES utf8mb4;

DELETE FROM city_slot_option WHERE id IS NOT NULL;
INSERT INTO city_slot_option (slot_name, option_value, sort_order, enabled, created_at, updated_at) VALUES
('city', '西安', 10, 1, NOW(), NOW()),
('city', '北京', 20, 1, NOW(), NOW()),
('city', '上海', 30, 1, NOW(), NOW()),
('city', '成都', 40, 1, NOW(), NOW()),
('location', '曲江', 10, 1, NOW(), NOW()),
('location', '小寨', 20, 1, NOW(), NOW()),
('location', '高新', 30, 1, NOW(), NOW()),
('location', '钟楼', 40, 1, NOW(), NOW()),
('location', '朝阳', 50, 1, NOW(), NOW()),
('location', '徐汇', 60, 1, NOW(), NOW()),
('location', '锦江', 70, 1, NOW(), NOW()),
('experienceGoal', '放松', 10, 1, NOW(), NOW()),
('experienceGoal', '社交', 20, 1, NOW(), NOW()),
('experienceGoal', '解压', 30, 1, NOW(), NOW()),
('experienceGoal', '治愈', 40, 1, NOW(), NOW()),
('experienceGoal', '刺激', 50, 1, NOW(), NOW()),
('companion', '独处', 10, 1, NOW(), NOW()),
('companion', '情侣', 20, 1, NOW(), NOW()),
('companion', '朋友', 30, 1, NOW(), NOW()),
('companion', '亲子', 40, 1, NOW(), NOW()),
('budget', '免费', 10, 1, NOW(), NOW()),
('budget', '100元内', 20, 1, NOW(), NOW()),
('budget', '200元内', 30, 1, NOW(), NOW()),
('budget', '300元内', 40, 1, NOW(), NOW()),
('activityType', '电影', 10, 1, NOW(), NOW()),
('activityType', '展览', 20, 1, NOW(), NOW()),
('activityType', '演出', 30, 1, NOW(), NOW()),
('activityType', '桌游', 40, 1, NOW(), NOW()),
('activityType', '运动', 50, 1, NOW(), NOW()),
('activityType', '探店', 60, 1, NOW(), NOW()),
('style', '安静', 10, 1, NOW(), NOW()),
('style', '文艺', 20, 1, NOW(), NOW()),
('style', '热闹', 30, 1, NOW(), NOW()),
('style', '刺激', 40, 1, NOW(), NOW()),
('duration', '1小时内', 10, 1, NOW(), NOW()),
('duration', '1-2小时', 20, 1, NOW(), NOW()),
('duration', '2-4小时', 30, 1, NOW(), NOW()),
('duration', '半天', 40, 1, NOW(), NOW()),
('duration', '全天', 50, 1, NOW(), NOW()),
('feature', '室内', 10, 1, NOW(), NOW()),
('feature', '户外', 20, 1, NOW(), NOW()),
('feature', '近地铁', 30, 1, NOW(), NOW()),
('feature', '近距离', 40, 1, NOW(), NOW()),
('feature', '少排队', 50, 1, NOW(), NOW()),
('feature', '交通方便', 60, 1, NOW(), NOW());

DELETE FROM activity_item WHERE source_type = 'PUBLIC';
INSERT INTO activity_item (
    source_type, owner_user_id, name, city, location,
    experience_goal, companion, budget, activity_type, style, duration, feature, duration_minutes,
    created_at, updated_at
) VALUES
('PUBLIC', NULL, '曲江艺术中心周末特展', JSON_ARRAY('西安'), JSON_ARRAY('曲江'), JSON_ARRAY('放松','治愈','解压'), JSON_ARRAY('独处','情侣','朋友','亲子'), JSON_ARRAY('100元内'), JSON_ARRAY('展览'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('2-4小时'), JSON_ARRAY('室内','近地铁','交通方便'), 150, NOW(), NOW()),
('PUBLIC', NULL, '小寨独立影院观影', JSON_ARRAY('西安'), JSON_ARRAY('小寨'), JSON_ARRAY('放松','治愈','解压'), JSON_ARRAY('独处','情侣','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('电影'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('1-2小时'), JSON_ARRAY('室内','近地铁','交通方便'), 120, NOW(), NOW()),
('PUBLIC', NULL, '高新室内攀岩体验课', JSON_ARRAY('西安'), JSON_ARRAY('高新'), JSON_ARRAY('解压','刺激','社交'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('200元内'), JSON_ARRAY('运动'), JSON_ARRAY('刺激'), JSON_ARRAY('1-2小时'), JSON_ARRAY('室内'), 120, NOW(), NOW()),
('PUBLIC', NULL, '钟楼商圈桌游主题夜', JSON_ARRAY('西安'), JSON_ARRAY('钟楼'), JSON_ARRAY('社交','解压','放松'), JSON_ARRAY('朋友','情侣','独处'), JSON_ARRAY('100元内'), JSON_ARRAY('桌游'), JSON_ARRAY('热闹'), JSON_ARRAY('2-4小时'), JSON_ARRAY('室内','近地铁','少排队','交通方便'), 150, NOW(), NOW()),
('PUBLIC', NULL, '朝阳小剧场开放麦', JSON_ARRAY('北京'), JSON_ARRAY('朝阳'), JSON_ARRAY('放松','解压','社交'), JSON_ARRAY('独处','情侣','朋友'), JSON_ARRAY('200元内'), JSON_ARRAY('演出'), JSON_ARRAY('热闹'), JSON_ARRAY('1-2小时'), JSON_ARRAY('室内','近地铁','交通方便'), 120, NOW(), NOW()),
('PUBLIC', NULL, '徐汇摄影艺术展', JSON_ARRAY('上海'), JSON_ARRAY('徐汇'), JSON_ARRAY('放松','治愈','解压'), JSON_ARRAY('独处','情侣','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('展览'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('2-4小时'), JSON_ARRAY('室内','近地铁','少排队'), 150, NOW(), NOW()),
('PUBLIC', NULL, '锦江城市书店读书会', JSON_ARRAY('成都'), JSON_ARRAY('锦江'), JSON_ARRAY('放松','治愈','社交'), JSON_ARRAY('独处','朋友','情侣'), JSON_ARRAY('100元内'), JSON_ARRAY('探店'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('1-2小时'), JSON_ARRAY('室内','交通方便'), 90, NOW(), NOW());
