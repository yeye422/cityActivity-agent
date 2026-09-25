-- 活动自然语言描述 + 语义向量索引。
-- 先执行基础 activity_item 建表/迁移，再执行本脚本。
USE city_db;

ALTER TABLE activity_item
    ADD COLUMN description TEXT NULL AFTER name;

CREATE TABLE IF NOT EXISTS activity_embedding (
    activity_id BIGINT NOT NULL,
    source_hash CHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    dimensions INT NOT NULL,
    embedding_json LONGTEXT NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (activity_id),
    CONSTRAINT fk_activity_embedding_activity
        FOREIGN KEY (activity_id) REFERENCES activity_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE activity_item
SET description = CASE name
    WHEN '曲江自然探索亲子日（示例）' THEN '面向亲子家庭的室内自然探索展览，通过互动装置和轻量讲解认识动植物，节奏舒缓，适合周末陪伴孩子。'
    WHEN '小寨独立电影映后交流（示例）' THEN '独立电影放映结束后安排映后交流，适合喜欢安静观影、电影文化和轻社交的人群。'
    WHEN '高新即兴喜剧夜（示例）' THEN '现场即兴喜剧演出，互动感强、节奏轻松热闹，适合情侣或朋友一起解压。'
    WHEN '钟楼城市夜跑社群（示例）' THEN '从钟楼附近集合的城市夜跑活动，以轻运动和社群交流为主，适合想在户外释放压力的人群。'
    WHEN '朝阳公园飞盘新手局（示例）' THEN '面向新手的户外飞盘体验，有基础教学和分组互动，运动量适中，强调朋友社交和参与感。'
    WHEN '朝阳当代设计导览（示例）' THEN '当代设计主题展览与导览，适合希望安静看展、了解设计作品和获得文艺体验的游客。'
    WHEN '朝阳独立乐队现场（示例）' THEN '独立乐队现场演出，音乐氛围浓烈、现场感和社交感较强，适合朋友或情侣夜间体验。'
    WHEN '朝阳周末咖啡读书会（示例）' THEN '在社区书房进行的小型读书与咖啡交流，环境安静，适合独处阅读或进行低压力社交。'
    WHEN '徐汇梧桐区建筑散步（示例）' THEN '沿徐汇梧桐街区进行的城市建筑漫步，边走边观察街区与建筑细节，适合慢节奏、文艺和放松体验。'
    WHEN '徐汇黑胶聆听会（示例）' THEN '在小型黑胶空间进行主题音乐聆听，环境安静，适合情侣约会或一个人慢慢放松。'
    WHEN '徐汇实验戏剧夜（示例）' THEN '小剧场实验戏剧演出，兼具文艺表达和现场互动，适合希望获得夜间文化体验的观众。'
    WHEN '徐汇桌游轻策局（示例）' THEN '以轻策略桌游为主的小规模组局，室内安静、参与感强，适合朋友进行较长时间互动。'
    WHEN '锦江公园晨跑社群（示例）' THEN '在锦江公园进行的晨间跑步社群活动，户外节奏轻快，适合运动、解压和认识新朋友。'
    WHEN '锦江手作陶艺体验（示例）' THEN '在陶艺工坊完成手作体验，可慢慢塑形和创作，过程安静治愈，适合独处、情侣或朋友。'
    WHEN '锦江喜剧开放麦（示例）' THEN '轻松热闹的喜剧开放麦，表演节奏快并带有现场互动，适合夜间解压和朋友聚会。'
    WHEN '锦江影展下午场（示例）' THEN '下午时段的主题影展放映，适合安静观影、文艺体验以及情侣或独处安排。'
    ELSE description
END
WHERE description IS NULL;
