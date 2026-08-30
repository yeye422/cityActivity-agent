-- 活动不再仅用“周六/周日”标签描述可用性，改为明确的有效日期区间。
-- 已有活动保持 NULL，表示历史数据暂不限制日期；补齐后可改为具体日期。
ALTER TABLE activity_item
    ADD COLUMN valid_from DATE NULL AFTER duration,
    ADD COLUMN valid_to DATE NULL AFTER valid_from,
    ADD COLUMN valid_start_time TIME NULL AFTER valid_to,
    ADD COLUMN valid_end_time TIME NULL AFTER valid_start_time,
    ADD INDEX idx_activity_valid_range (valid_from, valid_to);
