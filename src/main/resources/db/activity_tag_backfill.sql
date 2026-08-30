-- 公共活动多标签画像回填。
-- 目标：用户 Query 侧保持保守打标，Activity Item 侧使用更完整但有语义依据的标准标签。
-- 本脚本仅更新已知公共演示活动，不修改 city/location/budget/activity_type 等事实性字段。
-- 可重复执行：每次直接覆盖 mood/scene/style/duration 为统一标准画像。

USE city_db;
SET NAMES utf8mb4;

-- 展览 / 电影：强调放松、治愈、解压，以及独处/情侣/朋友等典型使用场景。
UPDATE activity_item
SET mood = JSON_ARRAY('放松','治愈','解压'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('安静','文艺'),
    duration = JSON_ARRAY('室内','交通方便','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name IN ('曲江艺术中心周末特展', '小寨独立影院观影');

UPDATE activity_item
SET mood = JSON_ARRAY('放松','治愈','解压'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('安静','文艺'),
    duration = JSON_ARRAY('室内','少排队','交通方便','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '徐汇摄影艺术展';

-- 运动：以解压、刺激为主，同时允许朋友/情侣社交场景。
UPDATE activity_item
SET mood = JSON_ARRAY('解压','刺激','社交'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('刺激','热闹'),
    duration = JSON_ARRAY('室内','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '高新室内攀岩体验课';

-- 桌游：典型社交 + 解压，同时具备轻松体验属性。
UPDATE activity_item
SET mood = JSON_ARRAY('社交','解压','放松'),
    scene = JSON_ARRAY('情侣','朋友'),
    style = JSON_ARRAY('热闹'),
    duration = JSON_ARRAY('室内','少排队','交通方便','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '钟楼商圈桌游主题夜';

-- 喜剧 / 开放麦：用户“轻松、解压”的演出需求应能得到直接标签匹配。
UPDATE activity_item
SET mood = JSON_ARRAY('放松','解压','社交'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('热闹'),
    duration = JSON_ARRAY('室内','交通方便','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name IN ('朝阳小剧场开放麦', '脱口秀开放麦', '高新周末即兴喜剧夜');

-- 话剧：偏文艺、安静，也可以承接放松/治愈/解压诉求。
UPDATE activity_item
SET mood = JSON_ARRAY('放松','治愈','解压'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('安静','文艺'),
    duration = JSON_ARRAY('室内','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '话剧小剧场演出';

-- 爵士：偏放松、治愈和社交，风格更文艺。
UPDATE activity_item
SET mood = JSON_ARRAY('放松','治愈','社交'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('安静','文艺'),
    duration = JSON_ARRAY('室内','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '爵士乐酒吧演出';

-- LiveHouse：以解压、社交、刺激为主，不强行标记“治愈”。
UPDATE activity_item
SET mood = JSON_ARRAY('解压','社交','刺激'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('热闹','刺激'),
    duration = JSON_ARRAY('室内','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = 'LiveHouse小型音乐会';

-- 老电影 / 独立电影映后交流：兼顾放松、治愈和文艺属性；映后交流增加社交属性。
UPDATE activity_item
SET mood = JSON_ARRAY('放松','治愈','解压'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('安静','文艺'),
    duration = JSON_ARRAY('室内','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '午夜场经典老电影';

UPDATE activity_item
SET mood = JSON_ARRAY('放松','治愈','社交'),
    scene = JSON_ARRAY('独处','情侣','朋友'),
    style = JSON_ARRAY('安静','文艺'),
    duration = JSON_ARRAY('室内','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '小寨独立电影映后交流';

-- 实景解谜：突出刺激、解压和社交，不扩成“治愈”等无依据标签。
UPDATE activity_item
SET mood = JSON_ARRAY('解压','刺激','社交'),
    scene = JSON_ARRAY('情侣','朋友'),
    style = JSON_ARRAY('刺激','热闹'),
    duration = JSON_ARRAY('半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '钟楼老城实景解谜局';

-- 读书会：放松、治愈、社交并存，但保持安静/文艺主风格。
UPDATE activity_item
SET mood = JSON_ARRAY('放松','治愈','社交'),
    scene = JSON_ARRAY('独处','朋友'),
    style = JSON_ARRAY('安静','文艺'),
    duration = JSON_ARRAY('室内','半天'),
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name = '锦江城市书店读书会';

-- 核对所有公共活动当前标签。
SELECT id, name, city, location, mood, scene, budget, activity_type, style, duration
FROM activity_item
WHERE source_type = 'PUBLIC'
ORDER BY id;
