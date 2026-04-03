-- 初始菜单配置
INSERT INTO `t_menu_config` (`menu_code`, `title`, `description`, `path`, `icon`, `position`, `sort_order`, `is_beta`) 
VALUES
('CHAT', 'AI 助手', '与 AI 进行知识问答对话', '/chat', 'chat_bubble', 'SIDEBAR', 1, 0),
('DASHBOARD', '面试控制台', '开始一场新的模拟面试', '/', 'dashboard', 'SIDEBAR', 2, 0),
('NOTES', '知识库管理', '同步与搜索你的个人笔记', '/notes', 'description', 'SIDEBAR', 3, 0),
('CODING', '算法刷题', '针对薄弱点进行专项算法训练', '/coding', 'code', 'SIDEBAR', 4, 0),
('PROFILE', '能力画像', '查看你的技术掌握度曲线', '/profile', 'analytics', 'SIDEBAR', 5, 0),
('MONITORING', '系统监控', '模型路由、熔断状态与调用统计', '/monitoring', 'monitoring', 'SIDEBAR', 6, 0),
('OPS', '运维中心', '查看 RAG 运行态与审计信息', '/ops', 'build', 'EXTENSION', 7, 0),
('SETTINGS', '模型配置', '统一配置 Agent 的模型参数', '/settings', 'tune', 'EXTENSION', 8, 0),
('MCP', 'MCP 工具台', '查看能力并发起 MCP 调用', '/mcp', 'hub', 'EXTENSION', 9, 0),
('INTENT_TREE', '意图树配置', '在线维护意图树阈值与策略', '/intent-tree', 'account_tree', 'EXTENSION', 10, 0),
('WORKSPACE', '扩展空间', '管理菜单布局与扩展模块入口', '/workspace', 'dashboard_customize', 'EXTENSION', 11, 0),
('PROMPTS', '提示词管理', '浏览、编辑和预览 AI 提示词模板', '/prompts', 'edit_note', 'EXTENSION', 12, 0)
ON DUPLICATE KEY UPDATE 
title=VALUES(title), 
description=VALUES(description), 
path=VALUES(path), 
icon=VALUES(icon), 
position=VALUES(position), 
sort_order=VALUES(sort_order), 
is_beta=VALUES(is_beta);

-- 初始意图树节点
INSERT INTO `t_intent_node` (`intent_code`, `name`, `description`, `parent_code`, `level`, `examples`, `slot_hints`, `task_type`, `enabled`)
VALUES
('INTERVIEW.START.GENERAL', '开启模拟面试', '开始一场模拟面试', 'INTERVIEW', 2,
 '["开始一场Java面试", "我们来一场Spring Boot模拟面试"]', '["topic", "skipIntro"]', 'INTERVIEW_START', 1),
('INTERVIEW.REPORT.GENERAL', '生成面试报告', '生成已结束面试的复盘报告', 'INTERVIEW', 2,
 '["生成报告", "给我这次面试总结"]', '["sessionId"]', 'INTERVIEW_REPORT', 1),
('CODING.PRACTICE.CHOICE', '刷选择题', '编程选择题训练请求', 'CODING.PRACTICE', 2,
 '["来一道Redis选择题", "出一道Java单选题"]', '["topic", "questionType=CHOICE", "difficulty", "count"]', 'CODING_PRACTICE', 1),
('CODING.PRACTICE.FILL', '刷填空题', '编程填空题训练请求', 'CODING.PRACTICE', 2,
 '["来一道JVM填空题", "出一道并发补全题"]', '["topic", "questionType=FILL", "difficulty", "count"]', 'CODING_PRACTICE', 1),
('CODING.PRACTICE.ALGORITHM', '刷算法题', '算法实现题训练请求', 'CODING.PRACTICE', 2,
 '["来一道数组算法题", "出一道链表算法题"]', '["topic", "questionType=ALGORITHM", "difficulty", "count"]', 'CODING_PRACTICE', 1),
('CODING.PRACTICE.SCENARIO', '刷场景题', '工程场景分析题训练请求', 'CODING.PRACTICE', 2,
 '["来一道缓存击穿场景题", "出一道Redis场景题"]', '["topic", "difficulty", "count"]', 'CODING_PRACTICE', 1),
('KNOWLEDGE.QA.GENERAL', '知识问答', '用户直接询问技术知识、概念解释、原理分析，如什么是XXX、如何实现XXX、XXX和YYY的区别等', 'KNOWLEDGE.QA', 2,
 '["什么是Redis的持久化机制", "Java的垃圾回收是怎么工作的", "解释一下Spring的IOC原理", "HashMap和ConcurrentHashMap的区别"]', '["topic"]', 'KNOWLEDGE_QA', 1),
('PROFILE.TRAINING.QUERY', '查询学习计划', '查询学习画像或学习建议', 'PROFILE', 2,
 '["查询我的学习计划", "我最近薄弱点是什么"]', '["mode"]', 'PROFILE_TRAINING_PLAN_QUERY', 1),
('UNKNOWN', '未知意图', '无法判定具体业务意图', NULL, 2,
 '["你好", "今天天气不错"]', '[]', 'UNKNOWN', 1)
ON DUPLICATE KEY UPDATE
name=VALUES(name),
description=VALUES(description),
examples=VALUES(examples),
slot_hints=VALUES(slot_hints),
task_type=VALUES(task_type),
enabled=VALUES(enabled);

-- 初始槽位精炼 few-shot 样例
INSERT INTO `t_intent_slot_refine_case` (`task_type`, `user_query`, `ai_output`, `sort_order`, `enabled`, `deleted`)
VALUES
('CODING_PRACTICE', '来2道Java选择题，简单点', '{"slots":{"topic":"Java","questionType":"CHOICE","difficulty":"easy","count":2,"skipIntro":null,"mode":""}}', 1, 1, 0),
('CODING_PRACTICE', '刷一道并发算法题', '{"slots":{"topic":"并发","questionType":"ALGORITHM","difficulty":"","count":1,"skipIntro":null,"mode":""}}', 2, 1, 0),
('INTERVIEW_START', '来一场Spring Boot面试，跳过自我介绍', '{"slots":{"topic":"Spring Boot","questionType":"","difficulty":"","count":null,"skipIntro":true,"mode":""}}', 3, 1, 0),
('PROFILE_TRAINING_PLAN_QUERY', '看下学习计划', '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":"learning"}}', 4, 1, 0)
ON DUPLICATE KEY UPDATE
ai_output=VALUES(ai_output),
sort_order=VALUES(sort_order),
enabled=VALUES(enabled),
deleted=VALUES(deleted);
