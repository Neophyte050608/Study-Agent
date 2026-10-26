-- Chat-owned structured long-term memory. MySQL is the sole truth source.

CREATE TABLE IF NOT EXISTS `t_chat_memory_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `category` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    `content` TEXT NOT NULL,
    `content_hash` CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    `status` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE',
    `version` BIGINT NOT NULL DEFAULT 1,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `deleted_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_memory_content` (`user_id`, `category`, `content_hash`),
    KEY `idx_chat_memory_user_status` (`user_id`, `status`, `updated_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin COMMENT='Chat structured memory truth';

CREATE TABLE IF NOT EXISTS `t_chat_memory_source` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `memory_id` BIGINT NOT NULL,
    `user_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `session_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `source_message_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `source_message_pk` BIGINT NOT NULL,
    `source_start` INT UNSIGNED NOT NULL,
    `source_end` INT UNSIGNED NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `invalidated_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_memory_source` (`memory_id`, `source_message_id`, `source_start`, `source_end`),
    KEY `idx_chat_memory_source_session` (`user_id`, `session_id`, `memory_id`),
    KEY `idx_chat_memory_source_message` (`source_message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin COMMENT='Verified source quotes for Chat memory';

CREATE TABLE IF NOT EXISTS `t_chat_memory_extraction_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `task_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `user_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `session_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `source_message_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `source_message_pk` BIGINT NOT NULL,
    `status` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
    `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `lease_until` DATETIME(6) DEFAULT NULL,
    `lease_token` VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL,
    `error_code` VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_memory_task_id` (`task_id`),
    UNIQUE KEY `uk_chat_memory_task_message` (`source_message_id`),
    KEY `idx_chat_memory_task_claim` (`status`, `next_attempt_at`, `lease_until`, `id`),
    KEY `idx_chat_memory_task_session` (`user_id`, `session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin COMMENT='Durable Chat memory extraction tasks';

CREATE TABLE IF NOT EXISTS `t_chat_memory_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `memory_id` BIGINT NOT NULL,
    `user_id` VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
    `memory_version` BIGINT NOT NULL,
    `target` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MILVUS',
    `operation` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    `status` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
    `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `lease_until` DATETIME(6) DEFAULT NULL,
    `lease_token` VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL,
    `error_code` VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_memory_event_id` (`event_id`),
    UNIQUE KEY `uk_chat_memory_outbox_event` (`memory_id`, `memory_version`, `target`, `operation`),
    KEY `idx_chat_memory_outbox_claim` (`status`, `next_attempt_at`, `lease_until`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin COMMENT='Transactional projection outbox';
