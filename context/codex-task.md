## 任务：学习画像系统激进重构

### 任务背景
- 已完成数据库schema重设计（5张新表替代原来的2张表）
- 支持多维度掌握度评估、动态衰减、能力曲线追踪
- 本地开发版本，可直接删表重建

### 核心任务

#### 1. 创建新的Entity类（替代LearningProfileDO和LearningEventDO）
需要创建以下Entity：
- LearningTrajectoryDO（学习轨迹）
- UserKnowledgeStateDO（用户知识状态）
- CapabilityCurveDO（能力曲线）
- TopicDifficultyLevelDO（主题难度）
- LearningDecayConfigDO（衰减配置）

#### 2. 创建对应的Mapper接口
- LearningTrajectoryMapper
- UserKnowledgeStateMapper
- CapabilityCurveMapper
- TopicDifficultyLevelMapper
- LearningDecayConfigMapper

#### 3. 重写LearningProfileAgent核心逻辑
需要实现以下核心方法：
- upsertEvent(LearningEvent) - 事件摄入
- updateKnowledgeState(userId, topic, event) - 更新知识状态
- calculateMasteryScore(userId, topic) - 计算掌握度（多维度）
- getProfileState(userId) - 获取用户画像
- snapshot(userId) - 生成快照
- recommend(userId, mode) - 个性化推荐

#### 4. 实现关键算法
- 多维度掌握度计算：mastery_score = (weighted_avg_score * 0.6) + (confidence * 0.3) + (recency_bonus * 0.1)
- 动态衰减：decay_factor(t, source, difficulty) = base_decay(t) * source_weight(source) * difficulty_factor(difficulty)
- 能力等级划分：Level 0-4 based on mastery_score ranges
- 推荐优先级：(1 - mastery_score) * 0.5 + (difficulty_level / max_difficulty) * 0.3 + (time_since_last_attempt / max_days) * 0.2

### 关键文件
- 新Entity: src/main/java/com/example/interview/entity/
- 新Mapper: src/main/java/com/example/interview/mapper/
- 核心服务: src/main/java/com/example/interview/service/LearningProfileAgent.java

### 约束
- 保持现有的接口签名兼容（snapshot、recommend等方法）
- 支持缓存注解（@Cacheable/@CacheEvict）
- 使用MyBatis-Plus的LambdaQueryWrapper
- 遵循现有的代码风格和命名规范

请先完成Entity和Mapper的创建，然后重写LearningProfileAgent的核心逻辑。
