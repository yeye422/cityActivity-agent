-- /chat 请求幂等表。
-- 同一 userId + Idempotency-Key 只能绑定同一请求指纹；成功响应持久化后可直接重放。
USE city_db;

CREATE TABLE IF NOT EXISTS chat_request_idempotency (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  response_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chat_request_idempotency (user_id, idempotency_key),
  KEY idx_chat_request_idempotency_status_time (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
