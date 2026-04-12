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
('SETTINGS', '模型配置', '统一配置 Agent 的模型参数', '/settings', 'tune', 'EXTENSION', 9, 0),
('MCP', 'MCP 工具台', '查看能力并发起 MCP 调用', '/mcp', 'hub', 'EXTENSION', 10, 0),
('INTENT_TREE', '意图树配置', '在线维护意图树阈值与策略', '/intent-tree', 'account_tree', 'EXTENSION', 11, 0),
('WORKSPACE', '扩展空间', '管理菜单布局与扩展模块入口', '/workspace', 'dashboard_customize', 'EXTENSION', 12, 0),
('PROMPTS', '提示词管理', '浏览、编辑和预览 AI 提示词模板', '/prompts', 'edit_note', 'EXTENSION', 13, 0)
ON DUPLICATE KEY UPDATE 
title=VALUES(title), 
description=VALUES(description), 
path=VALUES(path), 
icon=VALUES(icon), 
position=VALUES(position), 
sort_order=VALUES(sort_order), 
is_beta=VALUES(is_beta);