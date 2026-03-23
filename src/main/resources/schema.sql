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
