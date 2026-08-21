-- City Activity Agent 最终数据库初始化脚本
-- 用于全新环境部署
-- 已统一使用 city/activity 命名，不依赖历史迁移脚本

CREATE DATABASE IF NOT EXISTS city_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE city_db;

DROP TABLE IF EXISTS activity_item;

CREATE TABLE activity_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source_type VARCHAR(16) NOT NULL,
  owner_user_id BIGINT DEFAULT NULL,
  name VARCHAR(128) NOT NULL,
  activity_time JSON NOT NULL,
  mood JSON NOT NULL,
  scene JSON NOT NULL,
  budget JSON NOT NULL,
  activity_type JSON NOT NULL,
  style JSON NOT NULL,
  duration JSON NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_activity_source(source_type),
  INDEX idx_activity_owner(owner_user_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 示例活动数据
INSERT INTO activity_item
(source_type, owner_user_id, name, activity_time, mood, scene, budget, activity_type, style, duration, created_at, updated_at)
VALUES
('PUBLIC', NULL, '独立电影院观影', '["周六下午","周六晚上"]', '["放松","治愈"]', '["情侣","朋友"]', '["100元内"]', '["电影"]', '["安静","文艺"]', '["2小时"]', NOW(), NOW()),
('PUBLIC', NULL, '当代艺术馆特展', '["周六上午","周日下午"]', '["放松","治愈"]', '["情侣","独处"]', '["100元内","200元内"]', '["展览"]', '["安静","文艺"]', '["半天"]', NOW(), NOW()),
('PUBLIC', NULL, 'LiveHouse音乐会', '["周六晚上"]', '["刺激","社交"]', '["朋友"]', '["200元内"]', '["演出"]', '["热闹"]', '["3小时"]', NOW(), NOW()),
('PUBLIC', NULL, '室内攀岩体验', '["周六下午"]', '["刺激","解压"]', '["朋友"]', '["200元内"]', '["运动"]', '["刺激"]', '["2小时"]', NOW(), NOW()),
('PUBLIC', NULL, '文艺咖啡馆探店', '["周日上午"]', '["放松","治愈"]', '["情侣","独处"]', '["100元内"]', '["探店"]', '["安静","文艺"]', '["半天"]', NOW(), NOW());

SELECT COUNT(*) AS total_activities FROM activity_item;
