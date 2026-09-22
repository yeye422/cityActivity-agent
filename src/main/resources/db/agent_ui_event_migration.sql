-- Agent UI / SSE 可恢复事件日志。
-- 事件先持久化再推送；Last-Event-ID 重连只读取日志，不重新执行 Agent / Tool。
USE city_db;

CREATE TABLE IF NOT EXISTS agent_ui_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(128) NOT NULL,
  event_seq INT NOT NULL,
  event_json JSON NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_ui_event_cursor (user_id, session_id, trace_id, event_seq),
  KEY idx_agent_ui_event_session_id (user_id, session_id, id),
  KEY idx_agent_ui_event_trace (trace_id, event_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
