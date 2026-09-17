-- =====================================================
-- MusicHub - 数据库初始化脚本
-- 使用方式: mysql -u root -p < init.sql
-- =====================================================

CREATE DATABASE IF NOT EXISTS springweb_demo
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE springweb_demo;

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) DEFAULT 'user',
    nickname VARCHAR(30) DEFAULT '',
    avatar_path VARCHAR(500) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 音频表
CREATE TABLE IF NOT EXISTS audio (
    id INT AUTO_INCREMENT PRIMARY KEY,
    audio_name VARCHAR(255) NOT NULL,
    save_path VARCHAR(255) NOT NULL,
    song_name VARCHAR(255) NOT NULL,
    singer VARCHAR(255) NOT NULL,
    genre VARCHAR(255) NOT NULL DEFAULT '其他',
    source VARCHAR(255) DEFAULT '',
    introduction TEXT,
    lyric_path VARCHAR(500) DEFAULT '',
    collect_count INT DEFAULT 0,
    upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    cover_path VARCHAR(500) DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 收藏关系表
CREATE TABLE IF NOT EXISTS user_collect (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    audio_id INT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_audio (user_id, audio_id),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    FOREIGN KEY (audio_id) REFERENCES audio(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日播放记录：同一用户当天播放同一首歌会累计次数
CREATE TABLE IF NOT EXISTS user_audio_play (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    audio_id INT NOT NULL,
    play_date DATE NOT NULL,
    play_count INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_audio_date (user_id, audio_id, play_date),
    KEY idx_play_date (play_date),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    FOREIGN KEY (audio_id) REFERENCES audio(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 音乐评论表
CREATE TABLE IF NOT EXISTS audio_comment (
    id INT AUTO_INCREMENT PRIMARY KEY,
    audio_id INT NOT NULL,
    user_id INT NOT NULL,
    content VARCHAR(500) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_comment_audio_time (audio_id, create_time),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    FOREIGN KEY (audio_id) REFERENCES audio(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 评论点赞关系：同一用户只能给同一条评论点赞一次
CREATE TABLE IF NOT EXISTS comment_like (
    id INT AUTO_INCREMENT PRIMARY KEY,
    comment_id INT NOT NULL,
    user_id INT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_comment_user (comment_id, user_id),
    FOREIGN KEY (comment_id) REFERENCES audio_comment(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户自建歌单
CREATE TABLE IF NOT EXISTS user_playlist (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    name VARCHAR(80) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_playlist_user_update (user_id, update_time),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 歌单歌曲与播放顺序
CREATE TABLE IF NOT EXISTS playlist_song (
    id INT AUTO_INCREMENT PRIMARY KEY,
    playlist_id INT NOT NULL,
    audio_id INT NOT NULL,
    sort_order INT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_playlist_audio (playlist_id, audio_id),
    KEY idx_playlist_song_order (playlist_id, sort_order),
    FOREIGN KEY (playlist_id) REFERENCES user_playlist(id) ON DELETE CASCADE,
    FOREIGN KEY (audio_id) REFERENCES audio(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 助手会话与消息：按用户隔离，并保存当前歌曲上下文
CREATE TABLE IF NOT EXISTS assistant_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    title VARCHAR(100) NOT NULL DEFAULT '新对话',
    current_audio_id INT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_assistant_conversation_user_update (user_id, update_time),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    FOREIGN KEY (current_audio_id) REFERENCES audio(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS assistant_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_assistant_message_conversation (conversation_id, id),
    FOREIGN KEY (conversation_id) REFERENCES assistant_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prompt_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    version INT NOT NULL,
    template_text TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    applicable_strategy VARCHAR(30) NOT NULL DEFAULT 'production',
    active_name VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN status = 'published' THEN name ELSE NULL END) STORED,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    UNIQUE KEY uk_prompt_name_version (name, version),
    UNIQUE KEY uk_prompt_active_name (active_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS assistant_prompt_usage (
    assistant_message_id BIGINT PRIMARY KEY,
    prompt_version VARCHAR(120) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (assistant_message_id) REFERENCES assistant_message(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_conversation_state (
    conversation_id BIGINT PRIMARY KEY,
    user_id INT NULL,
    summary TEXT NOT NULL,
    recent_messages JSON NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_state_user_update (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_long_term_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    memory_key CHAR(64) NOT NULL,
    memory_type VARCHAR(30) NOT NULL DEFAULT 'preference',
    content VARCHAR(500) NOT NULL,
    normalized_content VARCHAR(500) NOT NULL DEFAULT '',
    importance DOUBLE NOT NULL DEFAULT 0.8,
    confidence DOUBLE NOT NULL DEFAULT 0.9,
    embedding JSON NULL,
    embedding_model VARCHAR(255) NULL,
    source_conversation_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    topic VARCHAR(200) NOT NULL DEFAULT '',
    topic_key CHAR(64) NOT NULL DEFAULT '',
    expires_at TIMESTAMP NULL,
    last_accessed_at TIMESTAMP NULL,
    access_count BIGINT NOT NULL DEFAULT 0,
    superseded_by BIGINT NULL,
    origin VARCHAR(20) NOT NULL DEFAULT 'automatic',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_memory_user_key (user_id, memory_key),
    KEY idx_agent_memory_user_update (user_id, updated_at),
    KEY idx_agent_memory_topic_status (user_id, topic_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_memory_setting (
    user_id INT PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_memory_capture (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    conversation_id BIGINT NULL,
    enabled TINYINT(1) NOT NULL,
    captured_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_memory_capture_request (user_id, request_id),
    KEY idx_agent_memory_capture_created (created_at),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Agent 策略执行审计：只保存策略、工具元数据和资源用量，不保存用户问题、回答或隐藏思维链
CREATE TABLE IF NOT EXISTS agent_execution_audit (
    trace_id VARCHAR(191) PRIMARY KEY,
    request_id VARCHAR(100) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    model VARCHAR(100) NOT NULL,
    requested_strategy VARCHAR(20) NOT NULL,
    selected_strategy VARCHAR(20) NOT NULL,
    strategy_reason VARCHAR(50) NOT NULL,
    cost_budget VARCHAR(20) NOT NULL,
    model_calls INT NOT NULL DEFAULT 0,
    tool_calls INT NOT NULL DEFAULT 0,
    tool_rounds INT NOT NULL DEFAULT 0,
    tool_executions JSON NOT NULL,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    latency_ms INT NOT NULL DEFAULT 0,
    budget_exceeded TINYINT(1) NOT NULL DEFAULT 0,
    stop_reason VARCHAR(50) NOT NULL DEFAULT '',
    finish_reason VARCHAR(50) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'success',
    error_code VARCHAR(80) NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_audit_created (created_at),
    KEY idx_agent_audit_strategy (selected_strategy, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 默认管理员账号 (密码: 123456)
INSERT INTO user (username, password, role) VALUES ('admin', '123456', 'admin')
ON DUPLICATE KEY UPDATE username = username;

-- 默认普通用户 (密码: 123456)
INSERT INTO user (username, password, role) VALUES ('user', '123456', 'user')
ON DUPLICATE KEY UPDATE username = username;
