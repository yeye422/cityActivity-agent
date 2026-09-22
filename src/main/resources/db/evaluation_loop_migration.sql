-- 评估闭环最小迁移：与当前 Mapper 的 city_* 表名保持一致。
-- 执行前请确认 city_sessions、city_messages、city_slot_option 已完成迁移。
USE city_db;

-- 兼容只执行本迁移的环境：业务表不存在时先创建最小完整结构。
-- 全新部署仍建议优先执行 database_init_final.sql，以同时写入示例活动数据。
CREATE TABLE IF NOT EXISTS activity_item (
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
  INDEX idx_activity_owner(owner_user_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_sessions (
  id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  phase VARCHAR(64) NOT NULL,
  slots JSON NOT NULL,
  last_recommendations JSON NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_city_sessions_user_time (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_messages (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  intent VARCHAR(64) NULL,
  agent_trace_id VARCHAR(128) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_city_messages_session_time (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_slot_option (
  id BIGINT NOT NULL AUTO_INCREMENT,
  slot_name VARCHAR(64) NOT NULL,
  option_value VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_slot_option (slot_name, option_value),
  KEY idx_city_slot_enabled (slot_name, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- activity_item 的 city/location/有效期字段已在上面的兼容建表定义中声明。
-- 不使用 ADD COLUMN IF NOT EXISTS，兼容不支持该语法的 MySQL/MariaDB 版本。

CREATE TABLE IF NOT EXISTS city_request_trace (
  id BIGINT NOT NULL AUTO_INCREMENT,
  trace_id VARCHAR(128) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  event_count INT NOT NULL DEFAULT 0,
  duration_ms BIGINT NULL,
  error_message TEXT NULL,
  trace_json JSON NOT NULL,
  expected_intent VARCHAR(64) NULL,
  expected_slots JSON NULL,
  expected_clarify_action VARCHAR(32) NULL,
  labeled_by BIGINT NULL,
  labeled_at DATETIME NULL,
  label_note VARCHAR(512) NULL,
  prompt_version VARCHAR(64) NULL,
  rule_version VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_request_trace_trace_id (trace_id),
  KEY idx_city_request_trace_user_time (user_id, created_at),
  KEY idx_city_request_trace_session_time (session_id, created_at),
  KEY idx_city_request_trace_label (expected_intent, labeled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS city_feedback (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  trace_id VARCHAR(128) NULL,
  session_id VARCHAR(64) NOT NULL,
  item_id BIGINT NULL,
  action VARCHAR(32) NOT NULL,
  rating INT NULL,
  reason VARCHAR(512) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_city_feedback_user_time (user_id, created_at),
  KEY idx_city_feedback_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS evaluation_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  eval_set_version VARCHAR(64) NOT NULL,
  eval_set_hash VARCHAR(64) NULL,
  git_commit VARCHAR(64) NULL,
  prompt_version VARCHAR(64) NULL,
  rule_version VARCHAR(64) NULL,
  model_version VARCHAR(128) NULL,
  total_traces INT NOT NULL DEFAULT 0,
  avg_score DECIMAL(10,4) NULL,
  metric_snapshot JSON NOT NULL,
  baseline_run_id VARCHAR(128) NULL,
  is_baseline TINYINT NOT NULL DEFAULT 0,
  baseline_name VARCHAR(128) NULL,
  passed TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_evaluation_run_id (run_id),
  KEY idx_evaluation_run_user_set_time (user_id, eval_set_version, created_at),
  KEY idx_evaluation_run_baseline (user_id, eval_set_version, eval_set_hash, is_baseline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS evaluation_case (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  case_id VARCHAR(128) NOT NULL,
  eval_set_version VARCHAR(64) NOT NULL,
  case_json JSON NOT NULL,
  source_trace_id VARCHAR(128) NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_evaluation_case_user_id (user_id, case_id),
  KEY idx_evaluation_case_set (user_id, eval_set_version, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 已存在的旧 city_feedback 表只需补充请求级关联字段。
SET @has_trace_id := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'city_feedback' AND COLUMN_NAME = 'trace_id'
);
SET @sql := IF(@has_trace_id = 0,
  'ALTER TABLE city_feedback ADD COLUMN trace_id VARCHAR(128) NULL AFTER user_id, ADD INDEX idx_city_feedback_trace (trace_id)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兼容已经存在的 evaluation_run：补充代码/评测集指纹以及显式 Baseline 字段。
SET @has_eval_set_hash := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_run' AND COLUMN_NAME = 'eval_set_hash'
);
SET @sql := IF(@has_eval_set_hash = 0,
  'ALTER TABLE evaluation_run ADD COLUMN eval_set_hash VARCHAR(64) NULL AFTER eval_set_version',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_git_commit := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_run' AND COLUMN_NAME = 'git_commit'
);
SET @sql := IF(@has_git_commit = 0,
  'ALTER TABLE evaluation_run ADD COLUMN git_commit VARCHAR(64) NULL AFTER eval_set_hash',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_is_baseline := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_run' AND COLUMN_NAME = 'is_baseline'
);
SET @sql := IF(@has_is_baseline = 0,
  'ALTER TABLE evaluation_run ADD COLUMN is_baseline TINYINT NOT NULL DEFAULT 0 AFTER baseline_run_id',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_baseline_name := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_run' AND COLUMN_NAME = 'baseline_name'
);
SET @sql := IF(@has_baseline_name = 0,
  'ALTER TABLE evaluation_run ADD COLUMN baseline_name VARCHAR(128) NULL AFTER is_baseline',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_baseline_index := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_run' AND INDEX_NAME = 'idx_evaluation_run_baseline'
);
SET @sql := IF(@has_baseline_index = 0,
  'ALTER TABLE evaluation_run ADD INDEX idx_evaluation_run_baseline (user_id, eval_set_version, eval_set_hash, is_baseline)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
