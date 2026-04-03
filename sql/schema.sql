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

-- 创建意图槽位精炼样例表
CREATE TABLE IF NOT EXISTS `t_intent_slot_refine_case` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_type` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '任务类型',
    `user_query` VARCHAR(500) NOT NULL COMMENT '用户输入样例',
    `ai_output` MEDIUMTEXT NOT NULL COMMENT '模型期望输出(JSON)',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_intent_slot_refine_case` (`task_type`, `user_query`, `deleted`),
    KEY `idx_intent_slot_refine_case_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意图槽位精炼样例表';

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

-- 创建入库配置表
CREATE TABLE IF NOT EXISTS `t_ingest_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `config_key` VARCHAR(128) NOT NULL COMMENT '配置唯一键',
    `paths` TEXT COMMENT '知识库路径配置',
    `ignore_dirs` VARCHAR(1000) COMMENT '忽略目录（逗号分隔）',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ingest_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识入库配置表';

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
    `parent_id` VARCHAR(128) COMMENT '父文档ID',
    `child_index` INT COMMENT '子块索引',
    `chunk_strategy` VARCHAR(64) COMMENT '切分策略',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_id` (`doc_id`),
    KEY `idx_file_path` (`file_path`),
    KEY `idx_parent_id` (`parent_id`),
    FULLTEXT KEY `ft_lexical_text` (`text`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='词法索引表';

-- 创建 RAG Parent 表
CREATE TABLE IF NOT EXISTS `t_rag_parent` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `parent_id` VARCHAR(128) NOT NULL COMMENT '父文档业务ID',
    `file_path` VARCHAR(255) NOT NULL COMMENT '来源文件路径',
    `section_path` VARCHAR(512) COMMENT '章节路径',
    `source_type` VARCHAR(64) COMMENT '来源类型',
    `knowledge_tags` VARCHAR(255) COMMENT '知识标签',
    `parent_text` MEDIUMTEXT COMMENT '父文档内容',
    `parent_hash` VARCHAR(128) COMMENT '父文档哈希',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_parent_id` (`parent_id`),
    KEY `idx_parent_file_path` (`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG父文档索引表';

-- 创建 RAG Child 表
CREATE TABLE IF NOT EXISTS `t_rag_child` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `child_id` VARCHAR(128) NOT NULL COMMENT '子文档业务ID',
    `parent_id` VARCHAR(128) NOT NULL COMMENT '父文档业务ID',
    `child_index` INT COMMENT '子块序号',
    `child_text` MEDIUMTEXT COMMENT '子块内容',
    `chunk_strategy` VARCHAR(64) COMMENT '切分策略',
    `vector_doc_id` VARCHAR(128) COMMENT '向量文档ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_child_id` (`child_id`),
    KEY `idx_child_parent_id` (`parent_id`),
    KEY `idx_vector_doc_id` (`vector_doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG子文档索引表';

-- 创建 RAG Trace 汇总表
CREATE TABLE IF NOT EXISTS `t_rag_trace` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `trace_id` VARCHAR(128) NOT NULL COMMENT '链路唯一追踪ID',
    `root_node_id` VARCHAR(128) COMMENT '根节点ID',
    `root_node_type` VARCHAR(64) COMMENT '根节点类型',
    `root_node_name` VARCHAR(255) COMMENT '根节点名称',
    `trace_status` VARCHAR(32) NOT NULL DEFAULT 'COMPLETED' COMMENT '链路状态',
    `node_count` INT NOT NULL DEFAULT 0 COMMENT '链路节点总数',
    `retrieval_node_count` INT NOT NULL DEFAULT 0 COMMENT '检索节点总数',
    `total_retrieved_docs` INT NOT NULL DEFAULT 0 COMMENT '召回文档数总和',
    `max_retrieved_docs` INT NOT NULL DEFAULT 0 COMMENT '单节点最大召回文档数',
    `web_fallback_used` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否发生过Web fallback',
    `started_at` DATETIME NOT NULL COMMENT '链路开始时间',
    `ended_at` DATETIME COMMENT '链路结束时间',
    `duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '链路总耗时(毫秒)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_trace_id` (`trace_id`),
    KEY `idx_rag_trace_started_at` (`started_at`),
    KEY `idx_rag_trace_status` (`trace_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG Trace 汇总表';

-- 创建 RAG Trace 节点明细表
CREATE TABLE IF NOT EXISTS `t_rag_trace_node` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `trace_id` VARCHAR(128) NOT NULL COMMENT '所属Trace ID',
    `node_id` VARCHAR(128) NOT NULL COMMENT '节点ID',
    `parent_node_id` VARCHAR(128) COMMENT '父节点ID',
    `node_type` VARCHAR(64) NOT NULL COMMENT '节点类型',
    `node_name` VARCHAR(255) COMMENT '节点名称',
    `node_status` VARCHAR(32) NOT NULL DEFAULT 'RUNNING' COMMENT '节点状态',
    `input_summary` TEXT COMMENT '输入摘要',
    `output_summary` TEXT COMMENT '输出摘要',
    `error_summary` TEXT COMMENT '错误摘要',
    `retrieved_docs` INT COMMENT '结构化召回文档数',
    `web_fallback_used` TINYINT(1) COMMENT '是否触发Web fallback',
    `started_at` DATETIME NOT NULL COMMENT '节点开始时间',
    `ended_at` DATETIME COMMENT '节点结束时间',
    `duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '节点耗时(毫秒)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_trace_node` (`trace_id`, `node_id`),
    KEY `idx_rag_trace_node_trace_id` (`trace_id`),
    KEY `idx_rag_trace_node_parent` (`parent_node_id`),
    KEY `idx_rag_trace_node_started_at` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG Trace 节点明细表';

-- 创建检索评测运行汇总表
CREATE TABLE IF NOT EXISTS `t_retrieval_eval_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` VARCHAR(128) NOT NULL COMMENT '评测运行唯一ID',
    `dataset_source` VARCHAR(64) NOT NULL DEFAULT 'manual' COMMENT '评测数据集来源',
    `run_label` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '评测运行展示标签',
    `experiment_tag` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '实验标签',
    `parameter_snapshot` JSON COMMENT '参数快照',
    `notes` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '运行备注',
    `total_cases` INT NOT NULL DEFAULT 0 COMMENT '样本总数',
    `hit_cases` INT NOT NULL DEFAULT 0 COMMENT 'Recall@5 命中样本数',
    `recall_at1` DOUBLE NOT NULL DEFAULT 0 COMMENT 'Recall@1 指标',
    `recall_at3` DOUBLE NOT NULL DEFAULT 0 COMMENT 'Recall@3 指标',
    `recall_at5` DOUBLE NOT NULL DEFAULT 0 COMMENT 'Recall@5 指标',
    `mrr` DOUBLE NOT NULL DEFAULT 0 COMMENT 'MRR 指标',
    `report_timestamp` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '报告时间戳字符串',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_retrieval_eval_run_id` (`run_id`),
    KEY `idx_retrieval_eval_run_created_at` (`created_at`),
    KEY `idx_retrieval_eval_run_source` (`dataset_source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检索评测运行汇总表';

-- 创建检索评测单样本结果表
CREATE TABLE IF NOT EXISTS `t_retrieval_eval_case` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` VARCHAR(128) NOT NULL COMMENT '所属运行ID',
    `case_index` INT NOT NULL DEFAULT 0 COMMENT '样本序号',
    `query` VARCHAR(500) NOT NULL COMMENT '查询文本',
    `expected_keywords` JSON COMMENT '预期关键词列表',
    `tag` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '样本标签',
    `hit` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否命中',
    `rank` INT NOT NULL DEFAULT -1 COMMENT '首次命中排名',
    `retrieved_snippets` JSON COMMENT '召回片段列表',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_retrieval_eval_case_run_index` (`run_id`, `case_index`),
    KEY `idx_retrieval_eval_case_run_id` (`run_id`),
    KEY `idx_retrieval_eval_case_hit` (`hit`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检索评测单样本结果表';

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

-- ==================== Web 聊天会话 ====================
CREATE TABLE IF NOT EXISTS `t_chat_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(128) NOT NULL,
    `user_id` VARCHAR(128) NOT NULL,
    `title` VARCHAR(255) NOT NULL DEFAULT '新对话',
    `context_summary` TEXT DEFAULT NULL COMMENT '压缩后的上下文摘要',
    `summary_up_to_msg_id` BIGINT DEFAULT NULL COMMENT '已纳入摘要的最新消息ID（增量压缩标记）',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id_updated` (`user_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Web 聊天会话';

CREATE TABLE IF NOT EXISTS `t_chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `message_id` VARCHAR(128) NOT NULL,
    `session_id` VARCHAR(128) NOT NULL,
    `role` VARCHAR(16) NOT NULL COMMENT 'user|assistant|system',
    `content_type` VARCHAR(32) NOT NULL DEFAULT 'text' COMMENT '预留多模态: text|image|file',
    `content` MEDIUMTEXT,
    `metadata` JSON COMMENT 'traceId, sources, taskType 等扩展元数据',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    KEY `idx_session_created` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Web 聊天消息';

CREATE TABLE IF NOT EXISTS `t_user_chat_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` VARCHAR(128) NOT NULL COMMENT '用户ID',
    `memory_text` TEXT DEFAULT NULL COMMENT '累积的跨会话记忆（结构化文本）',
    `last_session_id` VARCHAR(128) DEFAULT NULL COMMENT '最后纳入记忆的会话ID',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    `last_dream_at` DATETIME DEFAULT NULL COMMENT '上次记忆整理时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户跨会话记忆';

CREATE TABLE IF NOT EXISTS `t_prompt_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL COMMENT '模板标识符（如 task-router）',
    `category` VARCHAR(32) NOT NULL DEFAULT 'general' COMMENT '分类',
    `type` VARCHAR(16) NOT NULL DEFAULT 'TASK' COMMENT 'SYSTEM 或 TASK',
    `title` VARCHAR(128) DEFAULT NULL COMMENT '显示名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '模板用途描述',
    `content` MEDIUMTEXT NOT NULL COMMENT '模板内容（Jinjava 语法）',
    `is_builtin` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否内置模板（不可删除）',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词模板';

-- ==================== 知识库管理 Phase A ====================
-- 知识库表
CREATE TABLE IF NOT EXISTS `t_knowledge_base` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(128) NOT NULL COMMENT '知识库名称',
    `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE',
    `source_type` VARCHAR(64) DEFAULT 'LOCAL_VAULT' COMMENT '来源类型：LOCAL_VAULT/BROWSER_UPLOAD/OTHER',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kb_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库实体表';

-- 文档表（Phase A 允许为空；查询可按 RAG Parent 视图兜底）
CREATE TABLE IF NOT EXISTS `t_knowledge_document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `kb_id` BIGINT NOT NULL COMMENT '所属知识库ID',
    `doc_name` VARCHAR(255) NOT NULL COMMENT '文档名称',
    `status` VARCHAR(32) NOT NULL DEFAULT 'READY' COMMENT '状态：READY/PROCESSING/FAILED',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `source_type` VARCHAR(64) DEFAULT 'LOCAL_VAULT' COMMENT '来源类型',
    `source_location` VARCHAR(1024) COMMENT '源位置路径或URL',
    `chunk_count` INT NOT NULL DEFAULT 0 COMMENT '分块数量',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_kb_id` (`kb_id`),
    KEY `idx_doc_name` (`doc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档表';

-- 入库任务历史（Phase A 留档）
CREATE TABLE IF NOT EXISTS `t_ingestion_task_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` VARCHAR(128) NOT NULL COMMENT '任务ID',
    `pipeline_name` VARCHAR(128) NOT NULL COMMENT '管道名称',
    `source_type` VARCHAR(64) NOT NULL COMMENT '数据源类型',
    `status` VARCHAR(32) NOT NULL COMMENT '任务状态',
    `started_at` BIGINT NOT NULL COMMENT '开始时间(ms)',
    `ended_at` BIGINT NOT NULL COMMENT '结束时间(ms)',
    `duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '耗时',
    `summary` JSON COMMENT '摘要(Map结构)',
    `error_message` TEXT COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_source_type` (`source_type`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库任务历史表';

-- 分块启停控制表（Phase B 轻量控制，覆盖 t_rag_child 的展示启停）
CREATE TABLE IF NOT EXISTS `t_knowledge_chunk_ctrl` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `doc_id` VARCHAR(128) NOT NULL COMMENT '父文档ID（对应 parent_id）',
    `chunk_id` VARCHAR(128) NOT NULL COMMENT '子块ID（对应 child_id 或向量ID）',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用（仅前端展示控制）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_ctrl` (`doc_id`, `chunk_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识分块启停控制表';
