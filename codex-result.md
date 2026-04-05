# 学习画像系统激进重构任务结果

## 完成情况

### 1. 新 Entity 类
已创建以下 Entity，位置：`src/main/java/com/example/interview/entity/`

- `LearningTrajectoryDO`
- `UserKnowledgeStateDO`
- `CapabilityCurveDO`
- `TopicDifficultyLevelDO`
- `LearningDecayConfigDO`

对应文件：

- `src/main/java/com/example/interview/entity/LearningTrajectoryDO.java`
- `src/main/java/com/example/interview/entity/UserKnowledgeStateDO.java`
- `src/main/java/com/example/interview/entity/CapabilityCurveDO.java`
- `src/main/java/com/example/interview/entity/TopicDifficultyLevelDO.java`
- `src/main/java/com/example/interview/entity/LearningDecayConfigDO.java`

### 2. 新 Mapper 接口
已创建以下 Mapper，位置：`src/main/java/com/example/interview/mapper/`

- `LearningTrajectoryMapper`
- `UserKnowledgeStateMapper`
- `CapabilityCurveMapper`
- `TopicDifficultyLevelMapper`
- `LearningDecayConfigMapper`

对应文件：

- `src/main/java/com/example/interview/mapper/LearningTrajectoryMapper.java`
- `src/main/java/com/example/interview/mapper/UserKnowledgeStateMapper.java`
- `src/main/java/com/example/interview/mapper/CapabilityCurveMapper.java`
- `src/main/java/com/example/interview/mapper/TopicDifficultyLevelMapper.java`
- `src/main/java/com/example/interview/mapper/LearningDecayConfigMapper.java`

### 3. LearningProfileAgent 核心逻辑重写
已重写文件：

- `src/main/java/com/example/interview/service/LearningProfileAgent.java`

已实现并保留兼容的核心方法：

- `upsertEvent(LearningEvent)`
- `updateKnowledgeState(userId, topic, event)`
- `calculateMasteryScore(userId, topic)`
- `getProfileState(userId)`
- `snapshot(userId)`
- `recommend(userId, mode)`
- `getTopicCapabilityCurve(userId, topic)`

### 4. 关键算法实现情况

#### 多维度掌握度计算
已实现：

`mastery_score = (weighted_avg_score * 0.6) + (confidence * 0.3) + (recency_bonus * 0.1)`

#### 动态衰减
已实现按以下维度计算衰减权重：

- 时间衰减 `base_decay(t)`
- 来源权重 `source_weight(source)`
- 难度因子 `difficulty_factor(difficulty)`
- 衰减配置表 `t_learning_decay_config`

支持的衰减曲线：

- `EXPONENTIAL`
- `LINEAR`
- `SIGMOID`

#### 能力等级划分
已实现 `Level 0-4`：

- `0`: mastery `< 0.2`
- `1`: mastery `< 0.4`
- `2`: mastery `< 0.6`
- `3`: mastery `< 0.8`
- `4`: mastery `>= 0.8`

#### 推荐优先级
已实现：

`(1 - mastery_score) * 0.5 + (difficulty_level / max_difficulty) * 0.3 + (time_since_last_attempt / max_days) * 0.2`

并附加了轻量 mode bias，用于区分 `interview` 和 `coding` 推荐。

## 兼容性与约束落实

- 保留了现有 `snapshot`、`recommend` 等接口签名
- 保留了缓存注解：`@Cacheable`、`@CacheEvict`
- 查询使用 MyBatis-Plus `LambdaQueryWrapper`
- 命名与现有项目风格保持一致

## 当前实现说明

- 新服务实现已切换到新 5 张表：
  - `t_learning_trajectory`
  - `t_user_knowledge_state`
  - `t_capability_curve`
  - `t_topic_difficulty_level`
  - `t_learning_decay_config`
- 旧的 `LearningProfileDO`、`LearningEventDO` 仍保留在代码库中，但当前 `LearningProfileAgent` 已不再依赖它们
- 新 topic 若不存在全局难度记录，会自动创建默认难度配置
- 若没有匹配的衰减配置，会回退到默认衰减策略

## 验证结果

已执行编译校验：

```bash
mvn -q -DskipTests compile
```

结果：通过。

## 未完成项

- 未新增或补充自动化测试
- 未清理旧表对应的旧 Entity/Mapper
- 未执行真实数据库迁移或数据回填，仅按本地可删表重建前提完成代码重构
