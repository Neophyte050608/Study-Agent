-- Evolve Chat structured memory into a subject-based lifecycle model.
-- MySQL remains the sole truth source; Milvus remains a derived projection.

ALTER TABLE `t_chat_memory_item`
    ADD COLUMN `subject_key` VARCHAR(191) COLLATE utf8mb4_bin NULL AFTER `category`,
    ADD COLUMN `subject_cardinality` VARCHAR(16) COLLATE utf8mb4_bin NULL AFTER `subject_key`,
    ADD COLUMN `valid_from` DATETIME(6) NULL AFTER `version`,
    ADD COLUMN `valid_until` DATETIME(6) NULL AFTER `valid_from`,
    ADD COLUMN `origin_message_created_at` DATETIME(6) NULL AFTER `valid_until`,
    ADD COLUMN `origin_message_pk` BIGINT NULL AFTER `origin_message_created_at`,
    ADD COLUMN `origin_quote_start` INT UNSIGNED NULL AFTER `origin_message_pk`,
    ADD COLUMN `quarantine_reason` VARCHAR(64) COLLATE utf8mb4_bin NULL AFTER `origin_quote_start`;

-- Select stable source ordering from the authoritative message row. Source-row creation
-- time is deliberately not used because extraction may complete after the message.
UPDATE `t_chat_memory_item` AS item
LEFT JOIN (
    SELECT ranked.memory_id,
           ranked.message_created_at,
           ranked.source_message_pk,
           ranked.source_start
    FROM (
        SELECT source.memory_id,
               message.created_at AS message_created_at,
               source.source_message_pk,
               source.source_start,
               ROW_NUMBER() OVER (
                   PARTITION BY source.memory_id
                   ORDER BY message.created_at,
                            source.source_message_pk,
                            source.source_start,
                            source.id
               ) AS source_rank
        FROM `t_chat_memory_source` AS source
        JOIN `t_chat_message` AS message
          ON message.id = source.source_message_pk
         AND message.message_id = source.source_message_id
         AND message.session_id = source.session_id
         AND message.role = 'user'
         AND message.deleted = 0
        WHERE source.invalidated_at IS NULL
    ) AS ranked
    WHERE ranked.source_rank = 1
) AS earliest_source
  ON earliest_source.memory_id = item.id
SET item.subject_key = CONCAT(LOWER(item.category), '.legacy_', item.id),
    item.subject_cardinality = CASE
        WHEN item.category IN ('PREFERENCE', 'GOAL', 'CONSTRAINT') THEN 'SINGLE'
        ELSE 'MULTI'
    END,
    item.valid_from = item.created_at,
    item.origin_message_created_at = COALESCE(
        earliest_source.message_created_at,
        item.created_at
    ),
    item.origin_message_pk = COALESCE(earliest_source.source_message_pk, 0),
    item.origin_quote_start = COALESCE(earliest_source.source_start, 0);

ALTER TABLE `t_chat_memory_item`
    MODIFY COLUMN `subject_key` VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN `subject_cardinality` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN `valid_from` DATETIME(6) NOT NULL,
    MODIFY COLUMN `origin_message_created_at` DATETIME(6) NOT NULL,
    MODIFY COLUMN `origin_message_pk` BIGINT NOT NULL,
    MODIFY COLUMN `origin_quote_start` INT UNSIGNED NOT NULL,
    DROP INDEX `uk_chat_memory_content`,
    ADD COLUMN `active_singleton_marker` TINYINT
      GENERATED ALWAYS AS (
          CASE
              WHEN `status` = 'ACTIVE' AND `subject_cardinality` = 'SINGLE' THEN 1
              ELSE NULL
          END
      ) STORED,
    ADD COLUMN `active_content_marker` TINYINT
      GENERATED ALWAYS AS (
          CASE WHEN `status` = 'ACTIVE' THEN 1 ELSE NULL END
      ) STORED,
    ADD UNIQUE KEY `uk_chat_memory_active_singleton`
      (`user_id`, `category`, `subject_key`, `active_singleton_marker`),
    ADD UNIQUE KEY `uk_chat_memory_active_content`
      (`user_id`, `category`, `subject_key`, `content_hash`, `active_content_marker`),
    ADD KEY `idx_chat_memory_user_subject`
      (`user_id`, `category`, `subject_key`, `id`);

CREATE TABLE `t_chat_memory_evolution` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `from_memory_id` BIGINT NOT NULL,
    `to_memory_id` BIGINT NOT NULL,
    `relation_type` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `ck_chat_memory_evolution_relation`
      CHECK (`relation_type` IN ('SUPERSEDES', 'CONFLICTS_WITH')),
    UNIQUE KEY `uk_chat_memory_evolution_relation`
      (`user_id`, `from_memory_id`, `to_memory_id`, `relation_type`),
    KEY `idx_chat_memory_evolution_from`
      (`user_id`, `from_memory_id`, `created_at`, `id`),
    KEY `idx_chat_memory_evolution_to`
      (`user_id`, `to_memory_id`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin
  COMMENT='Append-only Chat memory lifecycle relationships';
