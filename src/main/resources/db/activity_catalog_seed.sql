-- 扩展活动目录演示数据：先执行 city_seed.sql、activity_model_v2.sql 和 activity_venue_session_migration.sql。
-- 本脚本可重复执行；场次日期会自动落在执行日后的最近一个周六。
USE city_db;
SET NAMES utf8mb4;
SET @next_saturday = DATE_ADD(CURDATE(), INTERVAL ((5 - WEEKDAY(CURDATE()) + 7) % 7) DAY);

INSERT INTO venue (name, venue_type, city, district, address, business_start_time, business_end_time,
                   supported_activities, transport_tags, environment_tags, price_note, reservation_required)
VALUES
('曲江自然探索馆（示例）', '展馆', '西安', '曲江', '曲江新区示例路 18 号', '09:00:00', '18:00:00', JSON_ARRAY('展览','亲子活动'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','亲子'), '成人 68 元，儿童 38 元', 1),
('小寨光影空间（示例）', '影院', '西安', '小寨', '小寨示例街 66 号', '10:00:00', '23:00:00', JSON_ARRAY('电影','映后交流'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','文艺'), '票价以场次为准', 1),
('高新喜剧盒子（示例）', '剧场', '西安', '高新', '高新示例大道 88 号', '14:00:00', '23:00:00', JSON_ARRAY('演出','即兴喜剧'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','热闹'), '单人 98 元起', 1),
('钟楼夜跑集合点（示例）', '户外场地', '西安', '钟楼', '钟楼广场示例入口', '18:00:00', '23:00:00', JSON_ARRAY('运动','夜跑'), JSON_ARRAY('近地铁'), JSON_ARRAY('户外','社交'), '免费，建议自备饮水', 0),
('朝阳飞盘草坪（示例）', '户外场地', '北京', '朝阳', '朝阳公园示例南门内', '08:00:00', '20:00:00', JSON_ARRAY('运动','飞盘'), JSON_ARRAY('近地铁'), JSON_ARRAY('户外','社交'), '新手体验 39 元', 1),
('朝阳设计中心（示例）', '展馆', '北京', '朝阳', '朝阳示例路 20 号', '10:00:00', '19:00:00', JSON_ARRAY('展览','导览'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','文艺'), '导览票 80 元', 1),
('朝阳声场 Livehouse（示例）', '演出场馆', '北京', '朝阳', '朝阳示例街 99 号', '17:00:00', '23:30:00', JSON_ARRAY('演出','音乐现场'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','热闹'), '单人 168 元起', 1),
('朝阳社区书房（示例）', '书店', '北京', '朝阳', '朝阳示例社区 6 号楼', '09:00:00', '21:00:00', JSON_ARRAY('探店','读书会'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','安静'), '消费自愿', 0),
('徐汇建筑漫步站（示例）', '户外场地', '上海', '徐汇', '徐汇示例路口集合', '08:00:00', '18:00:00', JSON_ARRAY('运动','城市漫步'), JSON_ARRAY('近地铁'), JSON_ARRAY('户外','文艺'), '免费，需预约', 1),
('徐汇黑胶客厅（示例）', '咖啡馆', '上海', '徐汇', '徐汇示例街 35 号', '12:00:00', '23:00:00', JSON_ARRAY('探店','音乐聆听'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','安静'), '含饮品 88 元', 1),
('徐汇实验剧场（示例）', '剧场', '上海', '徐汇', '徐汇示例大道 12 号', '14:00:00', '22:30:00', JSON_ARRAY('演出','戏剧'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','文艺'), '单人 180 元起', 1),
('徐汇桌游研究所（示例）', '桌游馆', '上海', '徐汇', '徐汇示例广场 B1', '11:00:00', '23:00:00', JSON_ARRAY('桌游','推理'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','安静'), '单人 58 元', 1),
('锦江晨跑驿站（示例）', '户外场地', '成都', '锦江', '锦江公园示例东门', '06:00:00', '22:00:00', JSON_ARRAY('运动','晨跑'), JSON_ARRAY('近地铁'), JSON_ARRAY('户外','社交'), '免费', 0),
('锦江陶艺工坊（示例）', '工作室', '成都', '锦江', '锦江示例巷 16 号', '10:00:00', '20:00:00', JSON_ARRAY('探店','手作'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','治愈'), '单人 158 元', 1),
('锦江喜剧俱乐部（示例）', '剧场', '成都', '锦江', '锦江示例路 77 号', '18:00:00', '23:00:00', JSON_ARRAY('演出','开放麦'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','热闹'), '单人 88 元', 1),
('锦江影展空间（示例）', '影院', '成都', '锦江', '锦江示例广场 3 层', '10:00:00', '22:00:00', JSON_ARRAY('电影','影展'), JSON_ARRAY('近地铁'), JSON_ARRAY('室内','文艺'), '单人 69 元', 1)
ON DUPLICATE KEY UPDATE
    venue_type = VALUES(venue_type), district = VALUES(district), address = VALUES(address),
    business_start_time = VALUES(business_start_time), business_end_time = VALUES(business_end_time),
    supported_activities = VALUES(supported_activities), transport_tags = VALUES(transport_tags),
    environment_tags = VALUES(environment_tags), price_note = VALUES(price_note),
    reservation_required = VALUES(reservation_required), active = 1;

INSERT INTO activity_item (source_type, owner_user_id, name, city, location, mood, scene, budget, activity_type, style, duration, duration_minutes, active, created_at, updated_at)
SELECT seed.source_type, seed.owner_user_id, seed.name, seed.city, seed.location, seed.mood, seed.scene, seed.budget, seed.activity_type, seed.style, seed.duration, seed.duration_minutes, 1, NOW(), NOW()
FROM (
    SELECT 'PUBLIC' source_type, NULL owner_user_id, '曲江自然探索亲子日（示例）' name, JSON_ARRAY('西安') city, JSON_ARRAY('曲江','近地铁') location, JSON_ARRAY('放松','治愈') mood, JSON_ARRAY('亲子') scene, JSON_ARRAY('100元内') budget, JSON_ARRAY('展览') activity_type, JSON_ARRAY('安静','文艺') style, JSON_ARRAY('室内','半天') duration, 120 duration_minutes
    UNION ALL SELECT 'PUBLIC', NULL, '小寨独立电影映后交流（示例）', JSON_ARRAY('西安'), JSON_ARRAY('小寨','近地铁'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','情侣','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('电影'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','交通方便'), 150
    UNION ALL SELECT 'PUBLIC', NULL, '高新即兴喜剧夜（示例）', JSON_ARRAY('西安'), JSON_ARRAY('高新','近地铁'), JSON_ARRAY('解压','社交'), JSON_ARRAY('朋友','情侣'), JSON_ARRAY('100元内'), JSON_ARRAY('演出'), JSON_ARRAY('热闹'), JSON_ARRAY('室内','交通方便'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '钟楼城市夜跑社群（示例）', JSON_ARRAY('西安'), JSON_ARRAY('钟楼','近地铁'), JSON_ARRAY('解压','刺激'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('免费'), JSON_ARRAY('运动'), JSON_ARRAY('热闹'), JSON_ARRAY('近距离','半天'), 90
    UNION ALL SELECT 'PUBLIC', NULL, '朝阳公园飞盘新手局（示例）', JSON_ARRAY('北京'), JSON_ARRAY('朝阳','近地铁'), JSON_ARRAY('解压','社交'), JSON_ARRAY('朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('运动'), JSON_ARRAY('热闹'), JSON_ARRAY('半天','近距离'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '朝阳当代设计导览（示例）', JSON_ARRAY('北京'), JSON_ARRAY('朝阳','近地铁'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('展览'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','半天'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '朝阳独立乐队现场（示例）', JSON_ARRAY('北京'), JSON_ARRAY('朝阳','近地铁'), JSON_ARRAY('社交','刺激'), JSON_ARRAY('朋友','情侣'), JSON_ARRAY('200元内'), JSON_ARRAY('演出'), JSON_ARRAY('热闹'), JSON_ARRAY('室内','交通方便'), 150
    UNION ALL SELECT 'PUBLIC', NULL, '朝阳周末咖啡读书会（示例）', JSON_ARRAY('北京'), JSON_ARRAY('朝阳','近地铁'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('探店'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','交通方便'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '徐汇梧桐区建筑散步（示例）', JSON_ARRAY('上海'), JSON_ARRAY('徐汇','近地铁'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('免费'), JSON_ARRAY('运动'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('近距离','半天'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '徐汇黑胶聆听会（示例）', JSON_ARRAY('上海'), JSON_ARRAY('徐汇','近地铁'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','情侣'), JSON_ARRAY('100元内'), JSON_ARRAY('探店'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','交通方便'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '徐汇实验戏剧夜（示例）', JSON_ARRAY('上海'), JSON_ARRAY('徐汇','近地铁'), JSON_ARRAY('放松','社交'), JSON_ARRAY('朋友','情侣'), JSON_ARRAY('200元内'), JSON_ARRAY('演出'), JSON_ARRAY('文艺','热闹'), JSON_ARRAY('室内','交通方便'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '徐汇桌游轻策局（示例）', JSON_ARRAY('上海'), JSON_ARRAY('徐汇','近地铁'), JSON_ARRAY('社交','解压'), JSON_ARRAY('朋友'), JSON_ARRAY('100元内'), JSON_ARRAY('桌游'), JSON_ARRAY('安静'), JSON_ARRAY('室内','少排队'), 180
    UNION ALL SELECT 'PUBLIC', NULL, '锦江公园晨跑社群（示例）', JSON_ARRAY('成都'), JSON_ARRAY('锦江','近地铁'), JSON_ARRAY('解压','社交'), JSON_ARRAY('独处','朋友'), JSON_ARRAY('免费'), JSON_ARRAY('运动'), JSON_ARRAY('热闹'), JSON_ARRAY('近距离','半天'), 90
    UNION ALL SELECT 'PUBLIC', NULL, '锦江手作陶艺体验（示例）', JSON_ARRAY('成都'), JSON_ARRAY('锦江','近地铁'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','情侣','朋友'), JSON_ARRAY('200元内'), JSON_ARRAY('探店'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','半天'), 150
    UNION ALL SELECT 'PUBLIC', NULL, '锦江喜剧开放麦（示例）', JSON_ARRAY('成都'), JSON_ARRAY('锦江','近地铁'), JSON_ARRAY('解压','社交'), JSON_ARRAY('朋友','情侣'), JSON_ARRAY('100元内'), JSON_ARRAY('演出'), JSON_ARRAY('热闹'), JSON_ARRAY('室内','交通方便'), 120
    UNION ALL SELECT 'PUBLIC', NULL, '锦江影展下午场（示例）', JSON_ARRAY('成都'), JSON_ARRAY('锦江','近地铁'), JSON_ARRAY('放松','治愈'), JSON_ARRAY('独处','情侣'), JSON_ARRAY('100元内'), JSON_ARRAY('电影'), JSON_ARRAY('安静','文艺'), JSON_ARRAY('室内','交通方便'), 150
) seed
WHERE NOT EXISTS (SELECT 1 FROM activity_item a WHERE a.source_type = 'PUBLIC' AND a.name = seed.name);

INSERT INTO activity_session (activity_id, venue_id, start_at, end_at, price, capacity, remaining_seats, status)
SELECT a.id, v.id, TIMESTAMP(@next_saturday, schedule.start_time), TIMESTAMP(@next_saturday, schedule.end_time),
       schedule.price, schedule.capacity, schedule.remaining_seats, 'OPEN'
FROM (
    SELECT '曲江自然探索亲子日（示例）' activity_name, '曲江自然探索馆（示例）' venue_name, '10:00:00' start_time, '12:00:00' end_time, 68.00 price, 30 capacity, 16 remaining_seats
    UNION ALL SELECT '小寨独立电影映后交流（示例）', '小寨光影空间（示例）', '15:00:00', '17:30:00', 58.00, 40, 12
    UNION ALL SELECT '高新即兴喜剧夜（示例）', '高新喜剧盒子（示例）', '19:30:00', '21:30:00', 98.00, 80, 28
    UNION ALL SELECT '钟楼城市夜跑社群（示例）', '钟楼夜跑集合点（示例）', '19:00:00', '20:30:00', 0.00, 50, 20
    UNION ALL SELECT '朝阳公园飞盘新手局（示例）', '朝阳飞盘草坪（示例）', '10:00:00', '12:00:00', 39.00, 24, 8
    UNION ALL SELECT '朝阳当代设计导览（示例）', '朝阳设计中心（示例）', '14:00:00', '16:00:00', 80.00, 30, 10
    UNION ALL SELECT '朝阳独立乐队现场（示例）', '朝阳声场 Livehouse（示例）', '20:00:00', '22:30:00', 168.00, 100, 35
    UNION ALL SELECT '朝阳周末咖啡读书会（示例）', '朝阳社区书房（示例）', '15:00:00', '17:00:00', 0.00, 20, 9
    UNION ALL SELECT '徐汇梧桐区建筑散步（示例）', '徐汇建筑漫步站（示例）', '09:30:00', '11:30:00', 0.00, 25, 14
    UNION ALL SELECT '徐汇黑胶聆听会（示例）', '徐汇黑胶客厅（示例）', '15:00:00', '17:00:00', 88.00, 18, 6
    UNION ALL SELECT '徐汇实验戏剧夜（示例）', '徐汇实验剧场（示例）', '19:30:00', '21:30:00', 180.00, 60, 18
    UNION ALL SELECT '徐汇桌游轻策局（示例）', '徐汇桌游研究所（示例）', '14:00:00', '17:00:00', 58.00, 12, 5
    UNION ALL SELECT '锦江公园晨跑社群（示例）', '锦江晨跑驿站（示例）', '08:30:00', '10:00:00', 0.00, 40, 22
    UNION ALL SELECT '锦江手作陶艺体验（示例）', '锦江陶艺工坊（示例）', '14:00:00', '16:30:00', 158.00, 16, 7
    UNION ALL SELECT '锦江喜剧开放麦（示例）', '锦江喜剧俱乐部（示例）', '19:30:00', '21:30:00', 88.00, 70, 31
    UNION ALL SELECT '锦江影展下午场（示例）', '锦江影展空间（示例）', '14:30:00', '17:00:00', 69.00, 50, 19
) schedule
JOIN activity_item a ON a.name = schedule.activity_name AND a.source_type = 'PUBLIC'
JOIN venue v ON v.name = schedule.venue_name AND JSON_CONTAINS(a.city, JSON_QUOTE(v.city))
WHERE NOT EXISTS (
    SELECT 1 FROM activity_session s
    WHERE s.activity_id = a.id AND s.venue_id = v.id
      AND s.start_at = TIMESTAMP(@next_saturday, schedule.start_time)
);

SELECT JSON_UNQUOTE(JSON_EXTRACT(city, '$[0]')) AS city, COUNT(*) AS public_activity_count
FROM activity_item
WHERE source_type = 'PUBLIC'
GROUP BY JSON_UNQUOTE(JSON_EXTRACT(city, '$[0]'));
