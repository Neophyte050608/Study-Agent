-- Flyway reference-data baseline.
-- All inserts preserve rows already present when an existing database adopts Flyway.

-- ==================== 意图树节点 ====================
-- Level 0: DOMAIN 域节点（enabled=0，仅用于树形结构，不参与意图分类）
INSERT IGNORE INTO `t_intent_node` (`intent_code`, `name`, `description`, `parent_code`, `level`, `examples`, `slot_hints`, `task_type`, `enabled`)
VALUES
('INTERVIEW', '模拟面试', '面试模拟相关的所有功能，包括开启面试、回答问题、生成报告', NULL, 0, '[]', '[]', NULL, 0),
('CODING', '编程训练', '编程刷题训练相关，包括选择题、填空题、算法题、场景题', NULL, 0, '[]', '[]', NULL, 0),
('KNOWLEDGE', '知识查询', '纯知识查询与概念解释', NULL, 0, '[]', '[]', NULL, 0),
('PROFILE', '学习画像', '学习画像与训练计划查询', NULL, 0, '[]', '[]', NULL, 0);

-- Level 1: CATEGORY 类目节点（enabled=0，仅用于树形结构，不参与意图分类）
INSERT IGNORE INTO `t_intent_node` (`intent_code`, `name`, `description`, `parent_code`, `level`, `examples`, `slot_hints`, `task_type`, `enabled`)
VALUES
('INTERVIEW.LIFECYCLE', '面试生命周期', '面试会话的开启、回答、报告等生命周期操作', 'INTERVIEW', 1, '[]', '[]', NULL, 0),
('CODING.PRACTICE', '刷题训练', '各种题型的编程训练', 'CODING', 1, '[]', '[]', NULL, 0),
('KNOWLEDGE.QA', '知识问答', '技术知识查询与概念解释', 'KNOWLEDGE', 1, '[]', '[]', NULL, 0),
('PROFILE.TRAINING', '训练计划', '学习画像与训练计划相关', 'PROFILE', 1, '[]', '[]', NULL, 0);

-- Level 2: LEAF 叶子节点（可执行意图）
-- ============================================================
-- 设计原则：
--   1. description 同时写"正面信号"和"负面排除"，帮 LLM 在边界 case 做对
--   2. examples 覆盖：正式说法 / 口语缩写 / 省略主语 / topic前置 / 中英混用
--   3. 每个意图的 examples 之间尽量拉开语义距离，避免重复句式
-- ============================================================
INSERT IGNORE INTO `t_intent_node` (`intent_code`, `name`, `description`, `parent_code`, `level`, `examples`, `slot_hints`, `task_type`, `enabled`)
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
;

-- ==================== 槽位精炼 few-shot 样例 ====================
-- 设计原则：
--   1. 每种 taskType 至少覆盖：最短输入 / 带topic / 口语化 / 带多个槽位
--   2. 样例之间拉开语义距离，别重复句式
INSERT IGNORE INTO `t_intent_slot_refine_case` (`task_type`, `user_query`, `ai_output`, `sort_order`, `enabled`, `deleted`)
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
 '{"slots":{"topic":"","questionType":"","difficulty":"","count":null,"skipIntro":null,"mode":"recommendation"}}', 42, 1, 0);

-- Core prompt templates formerly loaded from classpath seed files.
-- The content includes all upgrades that existed before Flyway became the source of truth.
INSERT IGNORE INTO `t_prompt_template`
(`name`, `category`, `type`, `title`, `description`, `content`, `is_builtin`, `deleted`)
VALUES
('interviewer', 'system', 'SYSTEM', '面试官', '内置提示词，由 Flyway 初始化', '你是一位资深技术面试官。你的职责是公正、专业地评估候选人的技术能力。
评分维度包括：知识点准确性、表达逻辑、深度理解、边界考虑。
请始终保持专业、客观的态度。', 1, 0),
('coding-coach', 'system', 'SYSTEM', '编码教练', '内置提示词，由 Flyway 初始化', '你是一位专业的技术教练。你的职责是帮助候选人提升技术能力。
根据候选人的水平调整难度，低分时回归基础，高分时深入进阶。', 1, 0),
('router', 'system', 'SYSTEM', '路由网关', '内置提示词，由 Flyway 初始化', '你是一个智能任务路由网关。你的职责是准确识别用户意图并分发到对应的处理模块。
必须输出严格 JSON，不允许多余文字。', 1, 0),
('knowledge-assistant', 'system', 'SYSTEM', '知识助手', '内置提示词，由 Flyway 初始化', '你是一个专业的知识问答助手。你的职责是基于检索到的知识库内容准确回答用户问题。
回答时必须引用证据编号标注信息来源，不确定时如实说明。', 1, 0),
('context-compressor', 'system', 'SYSTEM', '上下文压缩器', '内置提示词，由 Flyway 初始化', '你是一个对话上下文压缩器。你的职责是将对话历史压缩为结构化摘要，只保留关键信息。', 1, 0),
('task-router', 'interview', 'TASK', '任务路由', '内置提示词，由 Flyway 初始化', '你是一个智能任务路由网关 (TaskRouterAgent)。
支持的任务类型 (TaskType)：
1. INTERVIEW_START: 开启模拟面试（多轮对话评估）。包含：技术面试、综合面试。
2. CODING_PRACTICE: 题目练习。包含：算法题 (ALGORITHM)、选择题 (CHOICE)、填空题 (FILL)。
3. INTERVIEW_ANSWER: 回答面试问题。当用户正在面试中并回答问题时使用。
4. PROFILE_TRAINING_PLAN_QUERY: 查询学习计划、画像或进度。
6. KNOWLEDGE_QA: 知识问答。当用户直接询问技术知识、概念解释、原理分析、"什么是XXX"、"如何实现XXX"等问题时使用。
7. UNKNOWN: 无法识别意图，或者用户在闲聊、胡说八道。

请分析用户输入并决定执行的任务。
如果用户要求的是“选择题”、“填空题”或“算法题”，请路由至 CODING_PRACTICE，并提取 questionType。
如果用户要求的是“面试”，请路由至 INTERVIEW_START。
如果无法匹配上述任何任务，或者用户的话语没有明确意图，请路由至 UNKNOWN。

{% if history %}
上下文（对话历史）：
{{ history }}
{% endif %}

用户当前输入：{{ query }}

请严格以 JSON 格式输出：{ "taskType": "...", "topic": "...", "questionType": "...", "reason": "..." }', 1, 0),
('intent-tree-classifier', 'intent', 'TASK', '意图树分类', '内置提示词，由 Flyway 初始化', '你是统一意图路由分类器。目标：基于候选叶子意图与对话上下文，一次输出可执行的结构化路由结果。

【输入】
- 当前用户输入：{{ query }}
- 历史对话：{{ history }}
- 预筛域提示（可为空）：{{ domainHint }}
- 候选叶子意图列表：{{ leafIntents }}
- 判定阈值参考：confidenceThreshold={{ confidenceThreshold }}, minGap={{ minGap }}, ambiguityRatio={{ ambiguityRatio }}

【任务要求】
1) 从候选叶子里选择最匹配 intentId，并给出 taskType、confidence、reason。
2) 给出至少1个 candidates，按 score 降序，最多3个。
3) 抽取 slots（按语义尽量填充，无法确定可留空）：topic/questionType/difficulty/count/skipIntro/mode。
4) 产出三信号：topicSwitch、dialogAct、infoNovelty。
5) 产出话题信息：currentTopic、previousTopic。
6) 产出上下文策略 contextPolicy，枚举仅允许：CONTINUE/SWITCH/RETURN/SUMMARY/SAFE_MIN。

【dialogAct 枚举】
FOLLOW_UP | CLARIFICATION | NEW_QUESTION | COMPARISON | RETURN | SUMMARY

【策略映射建议】
- NEW_QUESTION/COMPARISON -> SWITCH
- RETURN -> RETURN
- SUMMARY -> SUMMARY
- FOLLOW_UP/CLARIFICATION -> CONTINUE（若明显换题也可给 SWITCH）

【输出格式（必须严格 JSON，不要 Markdown，不要解释）】
{
  "taskType": "KNOWLEDGE_QA",
  "intentId": "KNOWLEDGE.QA.GENERAL",
  "confidence": 0.0,
  "reason": "",
  "slots": {
    "topic": "",
    "questionType": "",
    "difficulty": "",
    "count": null,
    "skipIntro": null,
    "mode": ""
  },
  "candidates": [
    {
      "intentId": "",
      "taskType": "",
      "score": 0.0,
      "reason": "",
      "missingSlots": []
    }
  ],
  "topicSwitch": false,
  "dialogAct": "FOLLOW_UP",
  "infoNovelty": 0.5,
  "currentTopic": "",
  "previousTopic": "",
  "contextPolicy": "CONTINUE"
}

【约束】
- confidence、score、infoNovelty 范围必须是 0~1。
- 若无法确定具体任务，taskType 可给 UNKNOWN，但仍需给 candidates 与三信号。
- 一律输出合法 JSON。', 1, 0),
('intent-clarification', 'intent', 'TASK', '意图澄清', '内置提示词，由 Flyway 初始化', '你是一个“意图澄清助手”。当系统对用户意图把握不足时，你需要用最少的问题把意图澄清清楚。

你会收到：
- 用户输入：{{ query }}
- 上下文：{{ history }}
- 候选意图列表（按置信度从高到低，最多 3 个）：{{ candidates }}

要求：
1) 输出必须是纯文本，不要 markdown。
2) 问题要短，尽量让用户通过“回复编号”或“直接说意图名称”完成选择。
3) 如果候选意图需要关键槽位（如 topic/questionType），在同一句话里顺带问清楚。

输出格式示例：
我不太确定你想做哪一种：
1) xxx（简短说明）
2) yyy（简短说明）
3) zzz（简短说明）
回复 1/2/3 选择；如果是刷题，也可以补充“题型（算法/选择/填空）+主题”。', 1, 0),
('intent-slot-refine', 'intent', 'TASK', '槽位补全', '内置提示词，由 Flyway 初始化', '你是一个槽位补全助手。你需要根据用户输入补全指定 taskType 的槽位信息。

taskType: {{ taskType }}
历史上下文: {{ history }}
用户输入: {{ query }}

{% if cases %}
参考示例：
{% for case in cases %}
用户输入：{{ case.user_query }}
期望输出：{{ case.ai_response }}
{% endfor %}
{% endif %}

要求：
1) 只输出严格 JSON，不要 markdown，不要解释。
2) 不确定的字段留空字符串，count 用 null。
3) 不要编造用户未提及的信息。
4) questionType 仅允许 ALGORITHM/CHOICE/FILL。
5) difficulty 仅允许 easy/medium/hard。
6) excludedTopics 当用户明确表示"不要"、"排除"、"跳过"、"不想涉及"某些知识点时，填写为字符串数组（例如 ["动态规划", "贪心"]）；如果用户没有排除任何知识点，此字段可省略。

输出结构：
{
  "slots": {
    "topic": "",
    "questionType": "",
    "difficulty": "",
    "count": null,
    "skipIntro": null,
    "mode": "",
    "excludedTopics": []
  }
}', 1, 0),
('first-question', 'interview', 'TASK', '首题生成', '内置提示词，由 Flyway 初始化', '{{ skillBlock }}
你是一位技术面试官。
简历摘要：{{ resume }}
用户画像：{{ profileSnapshot }}
面试主题：{{ topic }}

{% if skipIntro %}
当前环节：【直接出题】
请跳过开场白和自我介绍，结合用户的简历内容、用户画像和面试主题，直接生成第一道面试题。
题目应具有针对性，可以从简历中的项目经验或核心技能点切入。
{% else %}
当前环节：【自我介绍】
请结合用户的简历内容，生成一段破冰的开场白和第一道面试题。第一题必须是让候选人做自我介绍。
例如：“你好！看了你的简历，发现你在微服务领域有不少经验。今天我们将进行 {{ topic }} 相关的面试。在正式开始前，能先简单做个自我介绍吗？”
{% endif %}

只输出题目本身，必须单行，不要标题、不要策略说明、不要 markdown。', 1, 0),
('evaluation', 'interview', 'TASK', '答案评估', '内置提示词，由 Flyway 初始化', '{{ skillBlock }}
你是一位技术面试官。

面试主题：{{ topic }}
当前难度：{{ difficultyGuide }}
追问状态：{{ followUpState }}
当前主题能力值：{{ topicMastery }}
本轮出题与追问策略：{{ strategyHint }}
历史画像：
{{ profileSnapshot }}

知识库上下文：
{{ context }}

证据索引：
{{ retrievalEvidence }}

{% if cases %}
以下是优质的评估示例供参考：
{% for case in cases %}
示例{{ loop.index }}：
{{ case.user_query }}
期望输出：{{ case.ai_response }}
{% endfor %}
{% endif %}

当前问题：{{ question }}
用户回答：{{ userAnswer }}

任务：
1. 从知识点准确性、表达逻辑、深度、边界考虑四个维度分别评分（0-100）。
2. 给出总分，并明确扣分原因。
3. 如果知识库中出现该题相关笔记但回答不全/不对，要在反馈中显式标注【笔记已覆盖但回答缺失】。
4. 如果处于同一阶段，下一题尽量基于用户当前回答继续深挖；如果策略提示已进入新阶段，请按新阶段的要求生成全新的题目。
5. 难度自适应：低分时回到基础概念；稳定高分时转场景题、原理题、手写思路题。

6. citations 仅能填写证据索引中出现的编号和摘要，不允许捏造。
7. conflicts 仅在回答与证据或常识冲突时填写，格式为“冲突点｜参考证据编号”。
8. nextQuestion 是必填字段，必须基于当前回答生成一道追问题。

输出必须是严格 JSON，不要包含 markdown 代码块，不要输出任何额外文字。
JSON 字段：score,accuracy,logic,depth,boundary,deductions(array),citations(array),conflicts(array),feedback,nextQuestion。', 1, 0),
('final-report', 'interview', 'TASK', '面试总结报告', '内置提示词，由 Flyway 初始化', '{{ skillBlock }}
你是技术面试复盘官。
面试主题：{{ topic }}
后续训练建议参考：
{{ targetedSuggestion }}
{{ baseContext }}

以下是近期完整答题记录：
{{ history }}

请输出最终复盘报告，必须严格使用以下标签：
<summary>整体总结（3-5句）</summary>
<incomplete>回答不全的点（按要点分行）</incomplete>
<weak>回答不好的点（按要点分行）</weak>
<wrong>回答错误的点（按要点分行）</wrong>
<obsidian_updates>可直接补充进 Obsidian 的内容建议（分点）</obsidian_updates>
<next_focus>下一轮针对性出题方向（分点）</next_focus>
要求：中文、具体、可执行，不要输出其他标签。', 1, 0),
('coding-question', 'coding', 'TASK', '编码题生成', '内置提示词，由 Flyway 初始化', '{{ skillBlock }}
你是一位专业的技术教练，请根据要求生成一道中文题目。
主题与类型：{{ topic }}
题型标识：{{ questionType }}
难度：{{ difficulty }}
用户画像：{{ profileSnapshot }}
输出要求：
1. 如果题型标识是 CHOICE，必须生成选择题；如果是 FILL，必须生成填空题；如果是 SCENARIO，必须生成场景题；如果是 ALGORITHM，生成算法题。
2. 只输出题目正文与必要的选项/约束，禁止输出答案。
3. 单段文本，不要 markdown，不要标题。', 1, 0),
('coding-evaluation', 'coding', 'TASK', '编码题评估', '内置提示词，由 Flyway 初始化', '{{ skillBlock }}
你是一位技术面试评估官，请评估候选人的回答文本。
主题：{{ topic }}
题型：{{ questionType }}
难度：{{ difficulty }}
题目：{{ question }}
回答：{{ answer }}
请输出严格 JSON：score,feedback,nextHint,nextQuestion。
score 范围 0-100 。', 1, 0),
('coding-next-question', 'coding', 'TASK', '编码追问', '内置提示词，由 Flyway 初始化', '{{ skillBlock }}
你是一位专业的技术教练，请根据当前答题质量生成下一道追问题。
主题与类型：{{ topic }}
题型标识：{{ questionType }}
难度：{{ difficulty }}
当前题目：{{ question }}
用户回答：{{ answer }}
当前得分：{{ score }}
要求：
1. 只输出下一题题干，保持与上一题相同的题型（如选择题、算法题）；
2. 若当前分数较低，下一题降一级难度并聚焦基础；
3. 若当前分数较高，下一题同主题进阶追问。', 1, 0),
('learning-plan', 'interview', 'TASK', '学习计划', '内置提示词，由 Flyway 初始化', '{{ skillBlock }}
你是一位学习教练，请基于用户状态生成 7 天学习计划。
主题：{{ topic }}
薄弱点：{{ weakPoint }}
近期表现：{{ recentPerformance }}
输出要求：
1. 使用纯文本，每天一行；
2. 每行包含：目标 + 练习动作 + 复盘动作；
3. 不要 markdown 标题。', 1, 0),
('coding-intent', 'coding', 'TASK', '编码意图识别', '内置提示词，由 Flyway 初始化', '你是一个刷题意图识别助手。你的任务是从用户输入中提取“刷题/练习”的关键参数，并输出严格 JSON。

字段说明：
- topic：主题/技术栈/知识点（如 Java、Redis、JVM、数组与字符串）。如果用户没说，输出空字符串。
- type：题型，只能是 "算法" / "选择" / "填空" / "场景"。如果用户没说，默认输出 "选择"。
- count：题目数量（整数）。如果用户没说，默认输出 1。
- difficulty：难度，只能是 "easy" / "medium" / "hard"。如果用户没说，默认输出 "medium"。

规则：
1) 只输出 JSON，不要 markdown，不要解释。
2) 不要编造用户未提及的 topic；不确定就给空字符串。
3) 如果用户表达不是刷题/出题需求，而是答题/提交答案/闲聊，仍然按默认值输出，但 topic 置空。

{% if cases %}
参考示例：
{% for case in cases %}
用户输入：{{ case.user_query }}
期望输出：{{ case.ai_response }}
{% endfor %}
{% endif %}

用户输入：{{ message }}', 1, 0),
('knowledge-qa', 'chat', 'TASK', '知识问答', '内置提示词，由 Flyway 初始化', '你是一个专业的知识问答助手。根据以下检索到的知识库内容，准确回答用户的问题。
如果知识库中没有相关信息，请如实说明你不确定，并尝试根据你的知识给出参考答案。
请在回答中引用证据编号（如 [1], [2]）标注信息来源。

{% if history %}
对话历史：
{{ history }}
{% endif %}

检索到的知识上下文：
{{ context }}

证据目录：
{{ evidence }}

用户问题：{{ question }}

请给出详细、准确的回答：

{% if dialogSignal != "" %}
【对话信号】{{ dialogSignal }}
{% endif %}', 1, 0),
('chat-context-compress', 'chat', 'TASK', '上下文压缩', '内置提示词，由 Flyway 初始化', '你是一个对话上下文压缩器。请将以下对话历史压缩为结构化摘要。

{% if existingSummary %}
【已有摘要】（上一轮压缩结果）：
{{ existingSummary }}
{% endif %}

【新增对话】：
{{ newMessages }}

请按以下 7 个维度提取并输出摘要（每个维度限 1-3 句话，无内容则写"无"）：

1. 用户意图：本次会话的核心目标是什么？
2. 讨论主题：涉及了哪些技术话题或知识点？
3. 用户表现：得分情况、薄弱领域、擅长领域（如适用）
4. 核心问答：最重要的 2-3 个问答对的精华（压缩到一句话）
5. 用户偏好：用户明确表达的偏好或要求
6. 当前活动：正在进行什么操作/讨论？
7. 后续步骤：已承诺或建议的下一步行动

要求：
- 只保留关键信息，剔除寒暄和重复内容
- 总长度控制在 300 字以内
- 纯文本输出，不要 markdown，不要 JSON', 1, 0),
('cross-session-memorize', 'chat', 'TASK', '跨会话记忆', '内置提示词，由 Flyway 初始化', '你是一个用户画像提取器。请从会话摘要中提取用户的关键特征，并与已有记忆合并。

{% if existingMemory %}
【已有用户记忆】：
{{ existingMemory }}
{% endif %}

【本次会话摘要】：
{{ sessionSummary }}

【会话标题】：{{ sessionTitle }}

请按以下维度输出合并后的用户画像（每个维度限 1-2 句话，无内容则省略该维度）：

1. 学习目标：用户正在准备什么？目标公司/岗位？
2. 技术栈：用户关注或正在学习的技术领域
3. 能力水平：用户的强项和弱项
4. 学习偏好：用户喜欢的交互方式、难度偏好
5. 历史要点：跨会话的重要结论或承诺

要求：
- 合并而非替换：保留已有记忆中仍然有效的信息，融合新信息
- 如果新会话信息与已有记忆矛盾，以新信息为准
- 总长度控制在 200 字以内
- 纯文本输出，不要 markdown，不要 JSON', 1, 0),
('auto-dream', 'chat', 'TASK', '记忆整理', '内置提示词，由 Flyway 初始化', '你是一个用户记忆整理器。请对用户的累积记忆进行定期整理和优化。

【当前用户记忆】：
{{ currentMemory }}

{% if recentSummaries %}
【近期会话摘要】（供参考，可能包含记忆中未覆盖的新信息）：
{{ recentSummaries }}
{% endif %}

请按以下规则整理记忆：

1. 去除过时信息：如果用户的目标、偏好或状态已经发生变化，以最新信息为准
2. 合并重复项：多次提到的相同信息合并为一条
3. 重要性排序：将最重要的信息（学习目标、持续关注的技术领域、明确偏好）放在前面
4. 补充遗漏：如果近期会话摘要中有记忆未覆盖的重要信息，补充进来

输出格式（按重要性排序的结构化文本，每个维度 1-3 句话）：
1. 学习目标：当前最主要的目标
2. 技术栈：正在关注的技术领域和学习方向
3. 能力水平：已知的强项和弱项
4. 学习偏好：交互方式、难度偏好等
5. 历史要点：重要的跨会话结论或承诺

要求：
- 总长度控制在 500 字以内（比日常合并的 200 字限制更宽松，允许保留更多上下文）
- 纯文本输出，不要 markdown，不要 JSON
- 只保留对未来交互有价值的信息', 1, 0);

-- ==================== 提示词模板 ====================
INSERT IGNORE INTO `t_prompt_template` (`name`, `category`, `type`, `title`, `description`, `content`, `is_builtin`)
VALUES
('batch-quiz-question', 'coding-coach', 'TASK', '批量选择题生成',
 '根据指定主题、难度和数量，一次性生成多道结构化选择题（JSON 数组）',
 '请生成 {{count}} 道关于「{{topic}}」的{{difficulty}}难度选择题。\n\n严格按以下 JSON 格式输出（不要输出任何其他内容，不要使用 markdown 标记包裹 JSON）：\n[\n  {\n    \"index\": 1,\n    \"stem\": \"题目描述\",\n    \"options\": [\"A. 选项1\", \"B. 选项2\", \"C. 选项3\", \"D. 选项4\"],\n    \"correctAnswer\": \"B\",\n    \"explanation\": \"详细解析\"\n  }\n]\n\n要求：\n1. 每题必须有且仅有4个选项（A/B/C/D）\n2. correctAnswer 只能是 A/B/C/D 中的一个大写字母\n3. explanation 要详细解释为什么选择该答案\n4. 题目要有区分度，难度符合 {{difficulty}} 级别\n5. 确保 JSON 格式合法可用\n{% if profileSnapshot %}\n候选人画像参考：{{profileSnapshot}}\n{% endif %}',
 1),
('ollama-local-route', 'knowledge-routing', 'TASK', 'Ollama 本地知识路由',
 '将用户问题与候选笔记节点渲染成发往 Ollama 的路由提示词，只允许返回候选集内的 id',
 '你是本地知识路由器。请只根据候选节点选择最相关的知识点 id。\n只输出 JSON，格式为 {\"matches\":[\"id1\",\"id2\"]}。\n不要输出解释，不要输出候选集外的 id，最多返回 {{maxMatches}} 个 id。\n\n用户问题：\n{{question}}\n\n候选节点：\n{{candidateList}}',
 1);

INSERT IGNORE INTO `t_prompt_template` (`name`, `category`, `type`, `title`, `description`, `content`, `is_builtin`)
VALUES
('turn-analyzer-system', 'knowledge-routing', 'SYSTEM', '轮次分析系统提示词',
 '分析多轮对话的话题切换信号与对话行为',
 '你是一个对话分析专家。分析用户的当前问题与之前的对话历史，判断对话中的话题变化信号。\n\n你需要输出以下 4 个字段的 JSON：\n- topicSwitch (boolean)：当前问题是否切换了话题。如果用户在延续同一技术领域的深入讨论，则为 false。\n- dialogAct (string)：对话行为类型，必须是以下之一：\n  - FOLLOW_UP：追问或深入同一话题\n  - CLARIFICATION：请求澄清某个细节\n  - NEW_QUESTION：完全不同的新话题\n  - COMPARISON：对比两个技术概念\n  - RETURN：回到之前讨论过的话题\n  - SUMMARY：请求总结\n- infoNovelty (number 0.0~1.0)：当前问题引入的信息新鲜度。追问同一小点为 0.1~0.3，同话题新角度为 0.4~0.6，全新话题为 0.7~1.0。\n- currentTopic (string)：当前问题的话题标签，用简短的中文描述（不超过 10 个字）。\n\n只输出 JSON，不要输出其他内容。',
 1),
('turn-analyzer-task', 'knowledge-routing', 'TASK', '轮次分析用户提示词',
 '结合历史话题与最近对话分析当前轮的对话信号',
 '之前讨论过的话题：\n{{ topicList }}\n\n上一个话题：{{ previousTopic }}\n\n最近对话历史：\n{{ recentHistory }}\n\n当前用户问题：{{ currentQuestion }}\n\n请分析当前问题的话题变化信号，输出 JSON。',
 1),
('knowledge-digest-system', 'knowledge-routing', 'SYSTEM', '知识摘要系统提示词',
 '从 RAG 检索结果提炼话题知识摘要',
 '你是一个知识提炼专家。将检索到的知识内容浓缩为一段精简的摘要，保留最关键的技术要点。\n\n要求：\n- 摘要不超过 200 字\n- 保留关键技术术语和核心概念\n- 去除冗余的上下文描述\n- 输出纯文本，不要使用 markdown 格式',
 1),
('knowledge-digest-task', 'knowledge-routing', 'TASK', '知识摘要用户提示词',
 '根据当前话题和检索内容生成短摘要',
 '当前话题：{{ topic }}\n\n以下是检索到的知识内容：\n{{ knowledgeContext }}\n\n请提炼一段不超过 200 字的知识摘要。',
 1);

-- 模型候选池种子数据
INSERT IGNORE INTO `t_model_candidate` (`name`, `display_name`, `provider`, `model`, `base_url`, `priority`, `is_primary`, `supports_thinking`, `route_type`)
VALUES
    ('deepseek-chat', 'DeepSeek Chat', 'openai', 'deepseek-chat', 'https://api.deepseek.com', 10, 1, 0, 'GENERAL'),
    ('deepseek-reasoner', 'DeepSeek Reasoner', 'openai', 'deepseek-reasoner', 'https://api.deepseek.com', 20, 0, 1, 'THINKING');

-- Add menu entries introduced after the historical reference-data baseline.
-- 初始菜单配置
INSERT IGNORE INTO `t_menu_config` (`menu_code`, `title`, `description`, `path`, `icon`, `position`, `sort_order`, `is_beta`)
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
('PROMPTS', '提示词管理', '浏览、编辑和预览 AI 提示词模板', '/prompts', 'edit_note', 'EXTENSION', 14, 0);
