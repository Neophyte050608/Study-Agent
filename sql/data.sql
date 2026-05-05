-- 初始菜单配置
INSERT INTO `t_menu_config` (`menu_code`, `title`, `description`, `path`, `icon`, `position`, `sort_order`, `is_beta`) 
VALUES
('CHAT', 'AI 助手', '与 AI 进行知识问答对话', '/chat', 'chat_bubble', 'SIDEBAR', 1, 0),
('DASHBOARD', '面试控制台', '开始一场新的模拟面试', '/', 'dashboard', 'SIDEBAR', 2, 0),
('NOTES', '知识库管理', '同步与搜索你的个人笔记', '/notes', 'description', 'SIDEBAR', 3, 0),
('CODING', '算法刷题', '针对薄弱点进行专项算法训练', '/coding', 'code', 'SIDEBAR', 4, 0),
('PROFILE', '能力画像', '查看你的技术掌握度曲线', '/profile', 'analytics', 'SIDEBAR', 5, 0),
('MONITORING', '系统监控', '模型路由、熔断状态与调用统计', '/monitoring', 'monitoring', 'SIDEBAR', 6, 0),
('MODEL_PROVIDERS', '模型管理', '多模型候补与健康监控管理', '/model-providers', 'hub', 'SIDEBAR', 7, 0),
('OPS', '运维中心', '查看 RAG 运行态与审计信息', '/ops', 'build', 'EXTENSION', 8, 0),
('RAG_DASHBOARD', 'RAG 监控', '实时检索质量仪表盘与告警', '/rag-dashboard', 'monitoring', 'EXTENSION', 9, 0),
('SETTINGS', '模型配置', '统一配置 Agent 的模型参数', '/settings', 'tune', 'EXTENSION', 10, 0),
('MCP', 'MCP 工具台', '查看能力并发起 MCP 调用', '/mcp', 'hub', 'EXTENSION', 11, 0),
('INTENT_TREE', '意图树配置', '在线维护意图树阈值与策略', '/intent-tree', 'account_tree', 'EXTENSION', 12, 0),
('WORKSPACE', '扩展空间', '管理菜单布局与扩展模块入口', '/workspace', 'dashboard_customize', 'EXTENSION', 13, 0),
('PROMPTS', '提示词管理', '浏览、编辑和预览 AI 提示词模板', '/prompts', 'edit_note', 'EXTENSION', 14, 0)
ON DUPLICATE KEY UPDATE 
title=VALUES(title), 
description=VALUES(description), 
path=VALUES(path), 
icon=VALUES(icon), 
position=VALUES(position), 
sort_order=VALUES(sort_order), 
is_beta=VALUES(is_beta);

-- =========================================================
-- 提示词模板升级（方案B：统一意图 + 三信号 + contextPolicy）
-- 说明：
-- 1) 仅在模板为内置模板(is_builtin=1)时覆盖内容，避免误伤手工定制模板。
-- 2) 运行时已不再依赖 domain-classifier，这里仅做“废弃说明”标记。
-- =========================================================
INSERT INTO `t_prompt_template`
(`name`, `category`, `type`, `title`, `description`, `content`, `is_builtin`, `deleted`)
VALUES
(
  'intent-tree-classifier',
  'router',
  'TASK',
  '统一意图树分类器',
  '单次输出意图决策+三信号+contextPolicy（方案B）',
  '你是统一意图路由分类器。目标：基于候选叶子意图与对话上下文，一次输出可执行的结构化路由结果。\n\n【输入】\n- 当前用户输入：{{ query }}\n- 历史对话：{{ history }}\n- 预筛域提示（可为空）：{{ domainHint }}\n- 候选叶子意图列表：{{ leafIntents }}\n- 判定阈值参考：confidenceThreshold={{ confidenceThreshold }}, minGap={{ minGap }}, ambiguityRatio={{ ambiguityRatio }}\n\n【任务要求】\n1) 从候选叶子里选择最匹配 intentId，并给出 taskType、confidence、reason。\n2) 给出至少1个 candidates，按 score 降序，最多3个。\n3) 抽取 slots（按语义尽量填充，无法确定可留空）：topic/questionType/difficulty/count/skipIntro/mode。\n4) 产出三信号：topicSwitch、dialogAct、infoNovelty。\n5) 产出话题信息：currentTopic、previousTopic。\n6) 产出上下文策略 contextPolicy，枚举仅允许：CONTINUE/SWITCH/RETURN/SUMMARY/SAFE_MIN。\n\n【dialogAct 枚举】\nFOLLOW_UP | CLARIFICATION | NEW_QUESTION | COMPARISON | RETURN | SUMMARY\n\n【策略映射建议】\n- NEW_QUESTION/COMPARISON -> SWITCH\n- RETURN -> RETURN\n- SUMMARY -> SUMMARY\n- FOLLOW_UP/CLARIFICATION -> CONTINUE（若明显换题也可给 SWITCH）\n\n【输出格式（必须严格 JSON，不要 Markdown，不要解释）】\n{\n  "taskType": "KNOWLEDGE_QA",\n  "intentId": "KNOWLEDGE.QA.GENERAL",\n  "confidence": 0.0,\n  "reason": "",\n  "slots": {\n    "topic": "",\n    "questionType": "",\n    "difficulty": "",\n    "count": null,\n    "skipIntro": null,\n    "mode": ""\n  },\n  "candidates": [\n    {\n      "intentId": "",\n      "taskType": "",\n      "score": 0.0,\n      "reason": "",\n      "missingSlots": []\n    }\n  ],\n  "topicSwitch": false,\n  "dialogAct": "FOLLOW_UP",\n  "infoNovelty": 0.5,\n  "currentTopic": "",\n  "previousTopic": "",\n  "contextPolicy": "CONTINUE"\n}\n\n【约束】\n- confidence、score、infoNovelty 范围必须是 0~1。\n- 若无法确定具体任务，taskType 可给 UNKNOWN，但仍需给 candidates 与三信号。\n- 一律输出合法 JSON。',
  1,
  0
)
ON DUPLICATE KEY UPDATE
`category` = IF(`is_builtin` = 1, VALUES(`category`), `category`),
`type` = IF(`is_builtin` = 1, VALUES(`type`), `type`),
`title` = IF(`is_builtin` = 1, VALUES(`title`), `title`),
`description` = IF(`is_builtin` = 1, VALUES(`description`), `description`),
`content` = IF(`is_builtin` = 1, VALUES(`content`), `content`),
`deleted` = IF(`is_builtin` = 1, 0, `deleted`);

UPDATE `t_prompt_template`
SET `description` = '已废弃：统一分类链路不再使用该模板（保留仅用于回滚排障）'
WHERE `name` = 'domain-classifier'
  AND `deleted` = 0
  AND `is_builtin` = 1;
