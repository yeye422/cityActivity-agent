-- City Activity Agent 最终数据库初始化脚本
-- 用于全新环境部署：九维活动槽位 + 独立预计耗时。

CREATE DATABASE IF NOT EXISTS city_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE city_db;

DROP TABLE IF EXISTS activity_item;

CREATE TABLE activity_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source_type VARCHAR(16) NOT NULL,
  owner_user_id BIGINT DEFAULT NULL,
  name VARCHAR(128) NOT NULL,
  city JSON NULL,
  location JSON NULL,
  experience_goal JSON NOT NULL,
  companion JSON NOT NULL,
  budget JSON NOT NULL,
  activity_type JSON NOT NULL,
  style JSON NOT NULL,
  duration JSON NOT NULL,
  feature JSON NOT NULL,
  duration_minutes INT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  valid_from DATE NULL,
  valid_to DATE NULL,
  valid_start_time TIME NULL,
  valid_end_time TIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_activity_source(source_type),
  INDEX idx_activity_owner(owner_user_id, source_type),
  CONSTRAINT chk_activity_duration_minutes
    CHECK (duration_minutes IS NULL OR (duration_minutes > 0 AND duration_minutes <= 1440))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 示例活动数据：duration 只保存时长；feature 保存室内/交通等客观属性。
INSERT INTO activity_item
(source_type, owner_user_id, name, experience_goal, companion, budget, activity_type, style,
 duration, feature, duration_minutes, created_at, updated_at)
VALUES
('PUBLIC', NULL, '独立电影院观影', '["放松","治愈"]', '["情侣","朋友"]', '["100元内"]', '["电影"]', '["安静","文艺"]', '["1-2小时"]', '["室内"]', 120, NOW(), NOW()),
('PUBLIC', NULL, '当代艺术馆特展', '["放松","治愈"]', '["情侣","独处"]', '["100元内","200元内"]', '["展览"]', '["安静","文艺"]', '["2-4小时"]', '["室内","少排队"]', 150, NOW(), NOW()),
('PUBLIC', NULL, 'LiveHouse音乐会', '["刺激","社交"]', '["朋友"]', '["200元内"]', '["演出"]', '["热闹"]', '["2-4小时"]', '["室内"]', 180, NOW(), NOW()),
('PUBLIC', NULL, '室内攀岩体验', '["刺激","解压"]', '["朋友"]', '["200元内"]', '["运动"]', '["刺激"]', '["1-2小时"]', '["室内"]', 120, NOW(), NOW()),
('PUBLIC', NULL, '文艺咖啡馆探店', '["放松","治愈"]', '["情侣","独处"]', '["100元内"]', '["探店"]', '["安静","文艺"]', '["1-2小时"]', '["室内","交通方便"]', 90, NOW(), NOW());

SELECT COUNT(*) AS total_activities FROM activity_item;

-- 评估闭环、会话和反馈表：继续执行 src/main/resources/db/evaluation_loop_migration.sql
-- 地点/具体场次：继续执行 src/main/resources/db/activity_venue_session_migration.sql
