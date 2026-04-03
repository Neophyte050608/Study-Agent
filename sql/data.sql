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

-- ==================== 意图树节点 ====================
-- Level 0: DOMAIN 域节点（enabled=0，仅用于树形结构，不参与意图分类）
INSERT INTO `t_intent_node` (`intent_code`, `name`, `description`, `parent_code`, `level`, `examples`, `slot_hints`, `task_type`, `enabled`)
VALUES
('INTERVIEW', '模拟面试', '面试模拟相关的所有功能，包括开启面试、回答问题、生成报告', NULL, 0, '[]', '[]', NULL, 0),
('CODING', '编程训练', '编程刷题训练相关，包括选择题、填空题、算法题、场景题', NULL, 0, '[]', '[]', NULL, 0),
('KNOWLEDGE', '知识查询', '纯知识查询与概念解释', NULL, 0, '[]', '[]', NULL, 0),
('PROFILE', '学习画像', '学习画像与训练计划查询', NULL, 0, '[]', '[]', NULL, 0)
ON DUPLICATE KEY UPDATE
name=VALUES(name), description=VALUES(description), level=VALUES(level), enabled=VALUES(enabled);

-- Level 1: CATEGORY 类目节点（enabled=0，仅用于树形结构，不参与意图分类）
INSERT INTO `t_intent_node` (`intent_code`, `name`, `description`, `parent_code`, `level`, `examples`, `slot_hints`, `task_type`, `enabled`)
VALUES
('INTERVIEW.LIFECYCLE', '面试生命周期', '面试会话的开启、回答、报告等生命周期操作', 'INTERVIEW', 1, '[]', '[]', NULL, 0),
('CODING.PRACTICE', '刷题训练', '各种题型的编程训练', 'CODING', 1, '[]', '[]', NULL, 0),
('KNOWLEDGE.QA', '知识问答', '技术知识查询与概念解释', 'KNOWLEDGE', 1, '[]', '[]', NULL, 0),
('PROFILE.TRAINING', '训练计划', '学习画像与训练计划相关', 'PROFILE', 1, '[]', '[]', NULL, 0)
ON DUPLICATE KEY UPDATE
name=VALUES(name), description=VALUES(description), parent_code=VALUES(parent_code), level=VALUES(level), enabled=VALUES(enabled);

-- Level 2: LEAF 叶子节点（可执行意图）
-- ============================================================
-- 设计原则：
--   1. description 同时写"正面信号"和"负面排除"，帮 LLM 在边界 case 做对
--   2. examples 覆盖：正式说法 / 口语缩写 / 省略主语 / topic前置 / 中英混用
--   3. 每个意图的 examples 之间尽量拉开语义距离，避免重复句式
-- ============================================================
INSERT INTO `t_intent_node` (`intent_code`, `name`, `description`, `parent_code`, `level`, `examples`, `slot_hints`, `task_type`, `enabled`)
VALUES

-- ======================== 面试域 ========================

('INTERVIEW.START.GENERAL', '开启模拟面试',
 '用户想要【开始】一场模拟面试，让 AI 扮演面试官对自己进行多轮技术提问和追问。核心信号词：面试、面我、考我、mock、模拟。注意区分：如果用户只是单纯提问一个知识点（如"什么是HashMap"）而没有任何面试/考核/模拟的意图，那是知识问答（KNOWLEDGE_QA），不是面试。面试的本质是"你来考我/问我"，知识问答的本质是"我来问你"',
 'INTERVIEW.LIFECYCLE', 2,
 '["面试", "来面试", "开始面试", "模拟面试", "来一场面试", "面我", "面一面", "面一下", "考考我", "考我", "来面我一下", "我想面试", "我要面试", "帮我模拟面试", "帮我mock一下", "mock", "模拟一下", "开始一场Java面试", "来一场Spring Boot模拟面试", "来一场Redis面试", "面试考一考我", "来一轮技术面", "模拟一场技术面试", "Java面试", "面个Java", "面Spring", "考我Java基础", "面试我", "你来面试我", "你问我我来答", "出题考我", "随便面面", "练习面试", "来面", "再面一场", "再来一轮", "换个方向面", "面试练习", "我准备面试帮我练练"]',
 '["topic", "skipIntro"]', 'INTERVIEW_START', 1),

('INTERVIEW.ANSWER.GENERAL', '面试回答',
 '用户在面试进行中，对 AI 面试官提出的问题进行作答。判定条件：对话历史中存在进行中的面试会话（上文有面试题目），且用户当前消息是在回答那道题目。如果没有面试上下文，则不应识别为此意图',
 'INTERVIEW.LIFECYCLE', 2,
 '["我觉得答案是...", "我的理解是这样的", "这个问题我认为", "应该是用synchronized", "可以用Redis分布式锁来解决", "首先需要考虑线程安全", "我会用HashMap因为", "主要有三个方面", "这块我比较熟悉，首先"]',
 '["sessionId", "userAnswer"]', 'INTERVIEW_ANSWER', 1),

('INTERVIEW.REPORT.GENERAL', '生成面试报告',
 '用户想查看面试结束后的复盘报告、评分总结。信号词：报告、总结、复盘、评价、打分、表现。注意：仅在有面试记录时有意义',
 'INTERVIEW.LIFECYCLE', 2,
 '["生成报告", "给我面试总结", "出报告", "面试复盘", "看看我面试表现怎么样", "总结一下刚才的面试", "我表现怎么样", "给我打个分", "评价一下我的回答", "复盘", "出面试报告", "这次面试怎么样"]',
 '["sessionId"]', 'INTERVIEW_REPORT', 1),

-- ======================== 刷题域 ========================

('CODING.PRACTICE.GENERAL', '刷题（通用）',
 '用户想做题/刷题/练习，但没有明确指定题型（选择/填空/算法/场景）。信号词：刷题、做题、练题、来道题、出题、练习。如果用户明确说了"选择题""算法题"等，应该匹配对应的具体题型意图，而不是这个通用意图',
 'CODING.PRACTICE', 2,
 '["刷题", "做题", "练题", "来道题", "出道题", "来几道题", "做几道", "练习", "出题", "训练", "来一道", "做一道", "再来一道", "换一道", "再做一道", "随便来道题", "整道题", "出个题"]',
 '["topic", "difficulty", "count"]', 'CODING_PRACTICE', 1),

('CODING.PRACTICE.CHOICE', '刷选择题',
 '用户明确要做选择题（单选/多选）。信号词：选择题、单选、多选、ABCD、选项',
 'CODING.PRACTICE', 2,
 '["来一道Redis选择题", "出一道Java单选题", "来道选择题", "做几道Spring选择题", "选择题练习", "来个多选题", "做道单选", "来道Java的选择题", "选择题"]',
 '["topic", "questionType=CHOICE", "difficulty", "count"]', 'CODING_PRACTICE', 1),

('CODING.PRACTICE.FILL', '刷填空题',
 '用户明确要做填空题或代码补全题。信号词：填空、补全、填写、补完',
 'CODING.PRACTICE', 2,
 '["来一道JVM填空题", "出一道并发补全题", "来道填空题", "代码补全练习", "填空", "补全题", "来道补全的", "做道填空"]',
 '["topic", "questionType=FILL", "difficulty", "count"]', 'CODING_PRACTICE', 1),

('CODING.PRACTICE.ALGORITHM', '刷算法题',
 '用户明确要做算法题/编程题/手写代码题。信号词：算法、编程题、手写代码、实现、LeetCode、力扣、写个XXX',
 'CODING.PRACTICE', 2,
 '["来一道数组算法题", "出一道链表算法题", "刷算法", "来道编程题", "来道算法题", "做一道LeetCode题", "刷一道力扣", "写一道", "手写一个排序", "来道hard", "来道简单的算法", "做道二叉树的题", "dp题来一道"]',
 '["topic", "questionType=ALGORITHM", "difficulty", "count"]', 'CODING_PRACTICE', 1),

('CODING.PRACTICE.SCENARIO', '刷场景题',
 '用户明确要做工程场景分析题/系统设计题。信号词：场景题、场景分析、系统设计、怎么设计、设计一个',
 'CODING.PRACTICE', 2,
 '["来一道缓存击穿场景题", "出一道Redis场景题", "来道场景分析题", "系统设计场景题", "场景题", "来个设计题", "怎么设计一个秒杀系统", "出道系统设计题"]',
 '["topic", "difficulty", "count"]', 'CODING_PRACTICE', 1),

-- ======================== 知识域 ========================

('KNOWLEDGE.QA.GENERAL', '知识问答',
 '用户纯粹想了解某个技术知识点、概念解释或原理分析，是"我问你答"的模式。典型句式："什么是X"、"解释一下X"、"X的原理"、"X和Y的区别"、"X怎么实现的"、"讲讲X"。【重要区分】如果用户说"面试""面我""考我""模拟"，那是面试（INTERVIEW_START）；如果用户说"来道题""刷题""练习"，那是刷题（CODING_PRACTICE）。知识问答不涉及任何考核或训练——用户只是单纯地想获取信息',
 'KNOWLEDGE.QA', 2,
 '["什么是Redis的持久化机制", "Java的垃圾回收是怎么工作的", "解释一下Spring的IOC原理", "HashMap和ConcurrentHashMap的区别", "TCP三次握手过程", "MySQL索引的底层数据结构是什么", "什么是CAP定理", "讲讲JVM内存模型", "说说volatile关键字", "Redis为什么这么快", "Spring Boot自动装配原理", "如何理解AOP", "线程池的核心参数有哪些", "什么时候用B+树", "帮我解释一下乐观锁和悲观锁", "RocketMQ和Kafka有什么区别"]',
 '["topic"]', 'KNOWLEDGE_QA', 1),

-- ======================== 画像域 ========================

('PROFILE.TRAINING.QUERY', '查询学习计划',
 '用户想查看自己的学习画像、能力分析、薄弱项或获取学习建议。信号词：学习计划、薄弱点、哪方面弱、进度、水平、推荐学什么',
 'PROFILE.TRAINING', 2,
 '["查询我的学习计划", "我最近薄弱点是什么", "看看我的学习进度", "能力画像", "给我出个学习建议", "我什么水平", "哪方面需要加强", "推荐我学什么", "我的掌握情况", "分析一下我的能力"]',
 '["mode"]', 'PROFILE_TRAINING_PLAN_QUERY', 1),

-- ======================== 兜底 ========================

('UNKNOWN', '未知意图',
 '无法归入以上任何业务意图的输入，包括：闲聊、问候、感谢、与技术面试/学习无关的话题。如果用户输入模糊但可能与面试/刷题/知识相关，应尝试匹配对应意图而非直接归为UNKNOWN',
 NULL, 2,
 '["你好", "今天天气不错", "谢谢", "哈哈", "再见", "你是谁", "你能做什么"]', '[]', 'UNKNOWN', 1)

ON DUPLICATE KEY UPDATE
name=VALUES(name),
description=VALUES(description),
parent_code=VALUES(parent_code),
level=VALUES(level),
examples=VALUES(examples),
slot_hints=VALUES(slot_hints),
task_type=VALUES(task_type),
enabled=VALUES(enabled);

-- ==================== 槽位精炼 few-shot 样例 ====================
-- 设计原则：
--   1. 每种 taskType 至少覆盖：最短输入 / 带topic / 口语化 / 带多个槽位
--   2. 样例之间拉开语义距离，别重复句式
INSERT INTO `t_intent_slot_refine_case` (`task_type`, `user_query`, `ai_output`, `sort_order`, `enabled`, `deleted`)
VALUES
-- INTERVIEW_START 面试启动
('INTERVIEW_START', '面试',
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 1, 1, 0),
('INTERVIEW_START', '来一场Spring Boot面试，跳过自我介绍',
 '{"slots":{"topic":"Spring Boot","questionType":"","difficulty":"","count":null,"skipIntro":true,"mode":""}}', 2, 1, 0),
('INTERVIEW_START', '考考我MySQL相关的',
 '{"slots":{"topic":"MySQL","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 3, 1, 0),
('INTERVIEW_START', '模拟面试Redis，直接出题',
 '{"slots":{"topic":"Redis","questionType":"","difficulty":"","count":null,"skipIntro":true,"mode":""}}', 4, 1, 0),
('INTERVIEW_START', '面个Java',
 '{"slots":{"topic":"Java","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 5, 1, 0),
('INTERVIEW_START', 'mock一下并发',
 '{"slots":{"topic":"并发","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 6, 1, 0),

-- INTERVIEW_ANSWER 面试回答
('INTERVIEW_ANSWER', '我觉得答案是使用HashMap，因为查找时间复杂度是O(1)',
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":"","userAnswer":"我觉得答案是使用HashMap，因为查找时间复杂度是O(1)"}}', 10, 1, 0),
('INTERVIEW_ANSWER', '主要有三个方面，第一是线程安全，第二是性能，第三是可维护性',
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":"","userAnswer":"主要有三个方面，第一是线程安全，第二是性能，第三是可维护性"}}', 11, 1, 0),

-- CODING_PRACTICE 刷题
('CODING_PRACTICE', '来2道Java选择题，简单点',
 '{"slots":{"topic":"Java","questionType":"CHOICE","difficulty":"easy","count":2,"skipIntro":null,"mode":""}}', 20, 1, 0),
('CODING_PRACTICE', '刷一道并发算法题',
 '{"slots":{"topic":"并发","questionType":"ALGORITHM","difficulty":"","count":1,"skipIntro":null,"mode":""}}', 21, 1, 0),
('CODING_PRACTICE', '出一道Redis场景题，中等难度',
 '{"slots":{"topic":"Redis","questionType":"SCENARIO","difficulty":"medium","count":1,"skipIntro":null,"mode":""}}', 22, 1, 0),
('CODING_PRACTICE', '刷题',
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 23, 1, 0),
('CODING_PRACTICE', '做道二叉树的题',
 '{"slots":{"topic":"二叉树","questionType":"ALGORITHM","difficulty":"","count":1,"skipIntro":null,"mode":""}}', 24, 1, 0),
('CODING_PRACTICE', '来道hard',
 '{"slots":{"topic":"","questionType":"ALGORITHM","difficulty":"hard","count":1,"skipIntro":null,"mode":""}}', 25, 1, 0),
('CODING_PRACTICE', '来3道Spring填空题',
 '{"slots":{"topic":"Spring","questionType":"FILL","difficulty":"","count":3,"skipIntro":null,"mode":""}}', 26, 1, 0),

-- KNOWLEDGE_QA 知识问答
('KNOWLEDGE_QA', '什么是JVM的内存模型',
 '{"slots":{"topic":"JVM内存模型","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 30, 1, 0),
('KNOWLEDGE_QA', 'Spring AOP和AspectJ有什么区别',
 '{"slots":{"topic":"Spring AOP vs AspectJ","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 31, 1, 0),
('KNOWLEDGE_QA', '讲讲volatile',
 '{"slots":{"topic":"volatile","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 32, 1, 0),
('KNOWLEDGE_QA', 'Redis为什么快',
 '{"slots":{"topic":"Redis性能","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":""}}', 33, 1, 0),

-- PROFILE_TRAINING_PLAN_QUERY 画像查询
('PROFILE_TRAINING_PLAN_QUERY', '看下学习计划',
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":"learning"}}', 40, 1, 0),
('PROFILE_TRAINING_PLAN_QUERY', '我哪方面比较弱',
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":"weakness"}}', 41, 1, 0),
('PROFILE_TRAINING_PLAN_QUERY', '推荐我学什么',
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":"recommendation"}}', 42, 1, 0)
ON DUPLICATE KEY UPDATE
ai_output=VALUES(ai_output),
sort_order=VALUES(sort_order),
enabled=VALUES(enabled),
deleted=VALUES(deleted);