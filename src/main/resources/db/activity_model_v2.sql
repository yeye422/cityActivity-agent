-- City Activity Agent V2 活动时间模型（未上线项目可直接执行）
-- 目标：activity_item 只保存活动属性，真实可参加时间统一由 activity_session 保存。
-- 执行前请备份数据库；执行后需同步更新 Java Mapper/Service。
USE city_db;

ALTER TABLE activity_item
    ADD COLUMN duration_minutes INT NULL AFTER duration,
    ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1 AFTER duration_minutes;

CREATE TABLE IF NOT EXISTS venue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    venue_type VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NULL,
    address VARCHAR(255) NULL,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    business_start_time TIME NULL,
    business_end_time TIME NULL,
    supported_activities JSON NULL,
    transport_tags JSON NULL,
    environment_tags JSON NULL,
    price_note VARCHAR(255) NULL,
    reservation_required TINYINT(1) NOT NULL DEFAULT 0,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_venue_city_name (city, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS activity_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    price DECIMAL(10,2) NULL,
    capacity INT NULL,
    remaining_seats INT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    registration_url VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_activity_time (activity_id, start_at),
    KEY idx_session_available_time (status, start_at, end_at),
    CONSTRAINT fk_session_v2_activity FOREIGN KEY (activity_id) REFERENCES activity_item(id),
    CONSTRAINT fk_session_v2_venue FOREIGN KEY (venue_id) REFERENCES venue(id),
    CONSTRAINT chk_session_v2_time CHECK (end_at > start_at),
    CONSTRAINT chk_session_v2_status CHECK (status IN ('OPEN', 'FULL', 'CANCELLED', 'ENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- V2 不再使用相对时间标签；确认所有活动已补充场次后再执行。
-- SELECT COUNT(*) AS activities_without_sessions
-- FROM activity_item a
-- LEFT JOIN activity_session s ON s.activity_id = a.id
-- WHERE s.id IS NULL;
-- activity_item.activity_time 已移除，活动时间统一存储在 activity_session。
