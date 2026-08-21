-- 城市周末活动测试数据。
-- 用途：在已完成 city_migration.sql 后，为 activity_item 补充公共活动库样本。

SET NAMES utf8mb4;

DELETE FROM activity_item WHERE source_type = 'PUBLIC';
INSERT INTO activity_item (
    source_type, owner_user_id, name, city, location,
    activity_time, mood, scene, budget, activity_type, style, duration,
    created_at, updated_at
) VALUES
('PUBLIC', NULL, '曲江艺术中心周末特展', JSON_ARRAY('西安'), JSON_ARRAY('曲江','近地铁'), JSON_ARRAY('周六下午','周日下午'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','情侣','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('展览'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','交通方便'), NOW(), NOW()),
('PUBLIC', NULL, '小寨独立影院观影', JSON_ARRAY('西安'), JSON_ARRAY('小寨','近地铁'), JSON_ARRAY('周六下午','周六晚上','周日晚上'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','情侣','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('电影'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','交通方便'), NOW(), NOW()),
('PUBLIC', NULL, '高新室内攀岩体验课', JSON_ARRAY('西安'), JSON_ARRAY('高新'), JSON_ARRAY('周六下午','周日上午'), JSON_ARRAY('解压','刺激'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('200元内'), JSON_ARRAY('运动'), JSON_ARRAY('刺激'), JSON_ARRAY('室内','半天'), NOW(), NOW()),
('PUBLIC', NULL, '钟楼商圈桌游主题夜', JSON_ARRAY('西安'), JSON_ARRAY('钟楼','近地铁'), JSON_ARRAY('周六晚上'), JSON_ARRAY('社交','解压'), JSON_ARRAY('朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('桌游'), JSON_ARRAY('热闹'), JSON_ARRAY('室内','少排队'), NOW(), NOW()),
('PUBLIC', NULL, '朝阳小剧场开放麦', JSON_ARRAY('北京'), JSON_ARRAY('朝阳','近地铁'), JSON_ARRAY('周六晚上'), JSON_ARRAY('解压','社交'), JSON_ARRAY('朋友','情侣'), JSON_ARRAY('200元内'), JSON_ARRAY('演出'), JSON_ARRAY('热闹'), JSON_ARRAY('室内','交通方便'), NOW(), NOW()),
('PUBLIC', NULL, '徐汇摄影艺术展', JSON_ARRAY('上海'), JSON_ARRAY('徐汇','近地铁'), JSON_ARRAY('周六下午','周日下午'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','情侣'), JSON_ARRAY('100元内'), JSON_ARRAY('展览'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','少排队'), NOW(), NOW()),
('PUBLIC', NULL, '锦江城市书店读书会', JSON_ARRAY('成都'), JSON_ARRAY('锦江'), JSON_ARRAY('周日上午','周日下午'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('探店'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','半天'), NOW(), NOW());
