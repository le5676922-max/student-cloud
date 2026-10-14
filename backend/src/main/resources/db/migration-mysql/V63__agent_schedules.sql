CREATE TABLE ai_schedule (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id INT NOT NULL,
    title VARCHAR(100) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    storage_key VARCHAR(100) NOT NULL,
    directory_path VARCHAR(500) NOT NULL,
    course_id INT NULL,
    tags VARCHAR(200) NULL,
    day_of_week INT NOT NULL,
    hour_of_day INT NOT NULL,
    minute_of_hour INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    next_run_at BIGINT NOT NULL,
    last_run_at BIGINT NULL,
    last_error VARCHAR(500) NULL,
    INDEX idx_ai_schedule_due (enabled, next_run_at),
    INDEX idx_ai_schedule_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
