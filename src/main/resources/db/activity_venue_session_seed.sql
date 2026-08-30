-- 演示数据：同一桌游主题活动可在两个场地举办；执行前先运行 activity_venue_session_migration.sql。
INSERT INTO venue (name, venue_type, city, district, address, business_start_time, business_end_time,
                   supported_activities, transport_tags, environment_tags, price_note, reservation_required)
VALUES
('骰子星球桌游馆', '桌游馆', '西安', '钟楼', '钟楼地铁站步行约 5 分钟', '12:00:00', '24:00:00',
 JSON_ARRAY('自由桌游', '桌游局'), JSON_ARRAY('近地铁'), JSON_ARRAY('社交', '热闹'), '自由桌游 48 元/人/3 小时', 0),
('猫头鹰桌游空间', '桌游馆', '西安', '小寨', '小寨地铁站附近', '13:00:00', '23:30:00',
 JSON_ARRAY('自由桌游', '桌游局'), JSON_ARRAY('近地铁'), JSON_ARRAY('安静', '适合聊天'), '自由桌游 58 元/人/3 小时', 1),
('朝阳小剧场', '剧场', '北京', '朝阳', '朝阳公园附近', '10:00:00', '22:30:00',
 JSON_ARRAY('开放麦', '演出'), JSON_ARRAY('近地铁'), JSON_ARRAY('热闹'), '演出价格以场次为准', 1)
ON DUPLICATE KEY UPDATE
    venue_type = VALUES(venue_type), district = VALUES(district), address = VALUES(address),
    business_start_time = VALUES(business_start_time), business_end_time = VALUES(business_end_time),
    supported_activities = VALUES(supported_activities), transport_tags = VALUES(transport_tags),
    environment_tags = VALUES(environment_tags), price_note = VALUES(price_note),
    reservation_required = VALUES(reservation_required), active = 1;

INSERT INTO activity_session (activity_id, venue_id, start_at, end_at, price, capacity, remaining_seats, status)
SELECT a.id, v.id, '2026-08-26 19:30:00', '2026-08-26 22:30:00', 68.00, 8, 4, 'OPEN'
FROM activity_item a JOIN venue v ON v.name = '骰子星球桌游馆' AND v.city = '西安'
WHERE a.name = '钟楼商圈桌游主题夜'
  AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.venue_id = v.id AND s.start_at = '2026-08-26 19:30:00');

INSERT INTO activity_session (activity_id, venue_id, start_at, end_at, price, capacity, remaining_seats, status)
SELECT a.id, v.id, '2026-08-27 19:30:00', '2026-08-27 22:30:00', 78.00, 8, 2, 'OPEN'
FROM activity_item a JOIN venue v ON v.name = '猫头鹰桌游空间' AND v.city = '西安'
WHERE a.name = '钟楼商圈桌游主题夜'
  AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.venue_id = v.id AND s.start_at = '2026-08-27 19:30:00');

INSERT INTO activity_session (activity_id, venue_id, start_at, end_at, price, capacity, remaining_seats, status)
SELECT a.id, v.id, '2026-08-29 19:30:00', '2026-08-29 21:30:00', 99.00, 80, 12, 'OPEN'
FROM activity_item a JOIN venue v ON v.name = '朝阳小剧场' AND v.city = '北京'
WHERE a.name = '朝阳小剧场开放麦'
  AND NOT EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.venue_id = v.id AND s.start_at = '2026-08-29 19:30:00');
