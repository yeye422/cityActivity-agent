-- 公共活动画像回填（九维槽位模型）。
-- 先执行 activity_slot_model_v3.sql；本脚本只补充活动画像，不重建活动数据，可重复执行。
-- duration 只保存活动时长标签；室内/交通/排队等统一写入 feature。
USE city_db;
SET NAMES utf8mb4;

UPDATE activity_item
SET
    experience_goal = CASE name
        WHEN '朝阳小剧场开放麦' THEN JSON_ARRAY('放松','解压','社交')
        WHEN '高新周末即兴喜剧夜' THEN JSON_ARRAY('放松','解压','社交')
        WHEN '锦江喜剧开放麦（示例）' THEN JSON_ARRAY('放松','解压','社交')
        WHEN '脱口秀开放麦' THEN JSON_ARRAY('放松','解压','社交')
        WHEN '话剧小剧场演出' THEN JSON_ARRAY('放松','治愈','社交')
        WHEN 'LiveHouse小型音乐会' THEN JSON_ARRAY('解压','社交','刺激')
        WHEN '爵士乐酒吧演出' THEN JSON_ARRAY('放松','治愈','社交')
        WHEN '小寨独立影院观影' THEN JSON_ARRAY('放松','治愈','解压')
        WHEN '小寨独立电影映后交流（示例）' THEN JSON_ARRAY('放松','治愈','解压')
        WHEN '午夜场经典老电影' THEN JSON_ARRAY('放松','治愈','解压')
        WHEN '徐汇摄影艺术展' THEN JSON_ARRAY('放松','治愈','解压')
        WHEN '钟楼商圈桌游主题夜' THEN JSON_ARRAY('社交','解压','放松')
        WHEN '徐汇桌游轻策局（示例）' THEN JSON_ARRAY('社交','解压','放松')
        WHEN '钟楼老城实景解谜局' THEN JSON_ARRAY('社交','解压','刺激')
        WHEN '高新室内攀岩体验课' THEN JSON_ARRAY('解压','刺激','社交')
        ELSE experience_goal
    END,
    companion = CASE name
        WHEN '朝阳小剧场开放麦' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '高新周末即兴喜剧夜' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '锦江喜剧开放麦（示例）' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '脱口秀开放麦' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '话剧小剧场演出' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN 'LiveHouse小型音乐会' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '爵士乐酒吧演出' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '小寨独立影院观影' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '小寨独立电影映后交流（示例）' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '午夜场经典老电影' THEN JSON_ARRAY('独处','情侣')
        WHEN '徐汇摄影艺术展' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '钟楼商圈桌游主题夜' THEN JSON_ARRAY('独处','情侣','朋友')
        WHEN '徐汇桌游轻策局（示例）' THEN JSON_ARRAY('独处','朋友')
        WHEN '钟楼老城实景解谜局' THEN JSON_ARRAY('情侣','朋友')
        WHEN '高新室内攀岩体验课' THEN JSON_ARRAY('独处','朋友')
        ELSE companion
    END,
    style = CASE name
        WHEN '钟楼老城实景解谜局' THEN JSON_ARRAY('热闹','刺激')
        WHEN '高新室内攀岩体验课' THEN JSON_ARRAY('刺激')
        WHEN 'LiveHouse小型音乐会' THEN JSON_ARRAY('热闹','刺激')
        WHEN '朝阳小剧场开放麦' THEN JSON_ARRAY('热闹')
        WHEN '高新周末即兴喜剧夜' THEN JSON_ARRAY('热闹')
        WHEN '锦江喜剧开放麦（示例）' THEN JSON_ARRAY('热闹')
        WHEN '脱口秀开放麦' THEN JSON_ARRAY('热闹')
        WHEN '爵士乐酒吧演出' THEN JSON_ARRAY('文艺','安静')
        WHEN '话剧小剧场演出' THEN JSON_ARRAY('文艺','安静')
        WHEN '小寨独立影院观影' THEN JSON_ARRAY('安静','文艺')
        WHEN '小寨独立电影映后交流（示例）' THEN JSON_ARRAY('安静','文艺')
        WHEN '午夜场经典老电影' THEN JSON_ARRAY('安静','文艺')
        WHEN '徐汇摄影艺术展' THEN JSON_ARRAY('安静','文艺')
        ELSE style
    END,
    duration = CASE name
        WHEN '朝阳小剧场开放麦' THEN JSON_ARRAY('半天')
        WHEN '高新周末即兴喜剧夜' THEN JSON_ARRAY('半天')
        WHEN '锦江喜剧开放麦（示例）' THEN JSON_ARRAY('半天')
        WHEN '脱口秀开放麦' THEN JSON_ARRAY('半天')
        WHEN '话剧小剧场演出' THEN JSON_ARRAY('半天')
        WHEN 'LiveHouse小型音乐会' THEN JSON_ARRAY('半天')
        WHEN '爵士乐酒吧演出' THEN JSON_ARRAY('半天')
        WHEN '小寨独立影院观影' THEN JSON_ARRAY('半天')
        WHEN '小寨独立电影映后交流（示例）' THEN JSON_ARRAY('半天')
        WHEN '午夜场经典老电影' THEN JSON_ARRAY('半天')
        WHEN '徐汇摄影艺术展' THEN JSON_ARRAY('半天')
        WHEN '钟楼商圈桌游主题夜' THEN JSON_ARRAY('半天')
        WHEN '徐汇桌游轻策局（示例）' THEN JSON_ARRAY('半天')
        WHEN '钟楼老城实景解谜局' THEN JSON_ARRAY('半天')
        WHEN '高新室内攀岩体验课' THEN JSON_ARRAY('半天')
        ELSE duration
    END,
    feature = CASE name
        WHEN '朝阳小剧场开放麦' THEN JSON_ARRAY('室内','交通方便')
        WHEN '高新周末即兴喜剧夜' THEN JSON_ARRAY('室内','交通方便')
        WHEN '锦江喜剧开放麦（示例）' THEN JSON_ARRAY('室内','交通方便')
        WHEN '脱口秀开放麦' THEN JSON_ARRAY('室内','交通方便')
        WHEN '话剧小剧场演出' THEN JSON_ARRAY('室内','交通方便')
        WHEN 'LiveHouse小型音乐会' THEN JSON_ARRAY('室内','交通方便')
        WHEN '爵士乐酒吧演出' THEN JSON_ARRAY('室内','交通方便')
        WHEN '小寨独立影院观影' THEN JSON_ARRAY('室内','交通方便')
        WHEN '小寨独立电影映后交流（示例）' THEN JSON_ARRAY('室内','交通方便')
        WHEN '午夜场经典老电影' THEN JSON_ARRAY('室内','交通方便')
        WHEN '徐汇摄影艺术展' THEN JSON_ARRAY('室内','少排队')
        WHEN '钟楼商圈桌游主题夜' THEN JSON_ARRAY('室内','少排队','交通方便')
        WHEN '徐汇桌游轻策局（示例）' THEN JSON_ARRAY('室内','少排队','交通方便')
        WHEN '钟楼老城实景解谜局' THEN JSON_ARRAY('交通方便')
        WHEN '高新室内攀岩体验课' THEN JSON_ARRAY('室内')
        ELSE feature
    END,
    updated_at = NOW()
WHERE source_type = 'PUBLIC'
  AND name IN (
    '朝阳小剧场开放麦','高新周末即兴喜剧夜','锦江喜剧开放麦（示例）','脱口秀开放麦',
    '话剧小剧场演出','LiveHouse小型音乐会','爵士乐酒吧演出','小寨独立影院观影',
    '小寨独立电影映后交流（示例）','午夜场经典老电影','徐汇摄影艺术展',
    '钟楼商圈桌游主题夜','徐汇桌游轻策局（示例）','钟楼老城实景解谜局','高新室内攀岩体验课'
  );

SELECT id, name, experience_goal, companion, style, duration, feature, duration_minutes
FROM activity_item
WHERE source_type = 'PUBLIC'
ORDER BY id;
