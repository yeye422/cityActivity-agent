CREATE TABLE IF NOT EXISTS city_preference_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    slot_name VARCHAR(40) NOT NULL,
    slot_value VARCHAR(100) NOT NULL,
    polarity VARCHAR(16) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'EXPLICIT',
    version INT NOT NULL DEFAULT 1,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_city_preference_fact (user_id, slot_name, slot_value, polarity),
    KEY idx_city_preference_user_active (user_id, active, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
