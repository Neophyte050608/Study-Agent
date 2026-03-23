-- 创建意图树表
CREATE TABLE IF NOT EXISTS `t_intent_node` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `intent_code` VARCHAR(128) NOT NULL COMMENT '意图唯一编码',
    `name` VARCHAR(128) NOT NULL COMMENT '展示名称',
    `description` TEXT COMMENT '语义描述，供 LLM 理解',
    `parent_code` VARCHAR(128) COMMENT '父节点编码，根节点为空',
    `level` INT NOT NULL DEFAULT 0 COMMENT '层级：0-DOMAIN, 1-CATEGORY, 2-TOPIC/LEAF',
    `examples` JSON COMMENT 'Few-shot 用户查询示例',
    `slot_hints` JSON COMMENT '槽位提取提示',
    `task_type` VARCHAR(64) COMMENT '绑定的任务类型',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_intent_code` (`intent_code`),
    KEY `idx_parent_code` (`parent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意图节点表';

-- 创建 Agent 配置表
CREATE TABLE IF NOT EXISTS `t_agent_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `agent_name` VARCHAR(128) NOT NULL COMMENT '代理名称',
    `provider` VARCHAR(64) COMMENT '模型提供商',
    `model_name` VARCHAR(128) NOT NULL COMMENT '绑定的模型名称',
    `api_key` VARCHAR(255) COMMENT 'API Key',
    `temperature` DOUBLE NOT NULL DEFAULT 0.7 COMMENT '采样温度',
    `system_prompt` TEXT COMMENT '系统提示词模板',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_name` (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent配置表';

-- 创建菜单配置表
CREATE TABLE IF NOT EXISTS `t_menu_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `menu_code` VARCHAR(128) NOT NULL COMMENT '菜单编码',
    `title` VARCHAR(128) NOT NULL COMMENT '菜单标题',
    `description` VARCHAR(255) COMMENT '菜单描述',
    `path` VARCHAR(255) COMMENT '路由路径',
    `icon` VARCHAR(128) COMMENT '图标',
    `position` VARCHAR(128) COMMENT '位置(SIDEBAR/EXTENSION)',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
    `is_beta` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否测试功能',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_menu_code` (`menu_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单配置表';

-- 创建面试会话表
CREATE TABLE IF NOT EXISTS `t_interview_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(128) NOT NULL COMMENT '会话唯一标识',
    `user_id` VARCHAR(128) NOT NULL COMMENT '用户标识',
    `current_stage` VARCHAR(64) NOT NULL COMMENT '当前面试阶段',
    `context_data` JSON COMMENT '面试上下文状态',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试会话表';

-- 创建学习画像表
CREATE TABLE IF NOT EXISTS `t_learning_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` VARCHAR(128) NOT NULL COMMENT '唯一标识',
    `total_events` INT NOT NULL DEFAULT 0 COMMENT '参与面试/练习总次数',
    `topic_metrics` JSON COMMENT '存储各知识点掌握度统计',
    `reliability_score` DOUBLE NOT NULL DEFAULT 0.0 COMMENT '画像可靠性分数',
    `last_updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习画像表';

-- 创建学习事件表
CREATE TABLE IF NOT EXISTS `t_learning_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_id` VARCHAR(128) NOT NULL COMMENT '唯一事件ID',
    `user_id` VARCHAR(128) NOT NULL COMMENT '用户ID',
    `source` VARCHAR(64) NOT NULL COMMENT '来源：INTERVIEW/PRACTICE',
    `topic` VARCHAR(255) COMMENT '知识点',
    `score` INT NOT NULL DEFAULT 0 COMMENT '得分',
    `weak_points` JSON COMMENT '薄弱点列表',
    `familiar_points` JSON COMMENT '熟悉点列表',
    `evidence` TEXT COMMENT '反馈报告原文',
    `timestamp` DATETIME NOT NULL COMMENT '事件发生时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习事件表';

-- 创建词法索引表
CREATE TABLE IF NOT EXISTS `t_lexical_index` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `doc_id` VARCHAR(128) NOT NULL COMMENT '对应向量库 ID',
    `text` MEDIUMTEXT COMMENT '文档正文',
    `file_path` VARCHAR(255) COMMENT '原始路径',
    `knowledge_tags` VARCHAR(255) COMMENT '标签',
    `source_type` VARCHAR(64) COMMENT '来源类型',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_id` (`doc_id`),
    KEY `idx_file_path` (`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='词法索引表';

-- 创建同步索引表
CREATE TABLE IF NOT EXISTS `t_sync_index` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_path` VARCHAR(255) NOT NULL COMMENT '唯一路径',
    `file_hash` VARCHAR(128) NOT NULL COMMENT '内容 MD5',
    `doc_ids` JSON COMMENT '该文件拆分后的所有文档 ID 列表',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_path` (`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步索引表';
