-- 为早期公共演示活动补齐可检索的有效日期与每日时段。
-- 仅更新 valid_from 为空的旧数据；已维护有效期的新活动和用户个人活动不受影响。
SET NAMES utf8mb4;

UPDATE activity_item
SET valid_from = '2026-08-25',
    valid_to = '2026-12-31',
    valid_start_time = CASE name
        WHEN '午夜场经典老电影' THEN '20:30:00'
        WHEN 'LiveHouse小型音乐会' THEN '19:30:00'
        WHEN '话剧小剧场演出' THEN '19:00:00'
        WHEN '脱口秀开放麦' THEN '19:30:00'
        WHEN '爵士乐酒吧演出' THEN '20:00:00'
        ELSE '10:00:00'
    END,
    valid_end_time = CASE name
        WHEN '午夜场经典老电影' THEN '23:30:00'
        WHEN 'LiveHouse小型音乐会' THEN '22:30:00'
        WHEN '话剧小剧场演出' THEN '21:30:00'
        WHEN '脱口秀开放麦' THEN '21:30:00'
        WHEN '爵士乐酒吧演出' THEN '23:00:00'
        ELSE '22:00:00'
    END,
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND valid_from IS NULL;

-- 验证：执行后不应再存在未维护日期的公共演示活动。
SELECT id, name, valid_from, valid_to, valid_start_time, valid_end_time
FROM activity_item
WHERE source_type = 'PUBLIC'
ORDER BY id;
