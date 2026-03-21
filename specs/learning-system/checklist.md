# InterviewReview 学习系统改造验收清单（V1.1）

## A. 架构与代码结构
- [x] 存在 `LearningProfileAgent` 且职责清晰（upsert/snapshot/recommend）
- [x] 存在统一 `LearningEvent` 模型，面试与刷题共用
- [x] `TaskRouterAgent` 可路由画像相关任务
- [x] `A2ABus` 抽象不被破坏，内存与 RocketMQ 实现可切换

## B. 缺陷修复验收（必须完成）
- [x] 刷题结果可写入统一画像（不再仅面试写画像）
- [x] 面试与刷题画像写入字段口径一致（topic/score/weak/familiar）
- [x] 画像融合具备 source 权重与时间衰减
- [x] 画像结果可输出弱项排行与熟项排行

## C. 功能兼容性
- [x] `/api/start` `/api/answer` `/api/report` 行为兼容
- [x] `/api/task/dispatch` 兼容现有任务类型
- [x] 旧画像文件可被兼容读取与补齐默认字段

## D. 训练闭环验收
- [x] 面试出题可读取画像快照并体现针对性
- [x] 刷题出题可读取画像快照并体现针对性
- [x] 推荐接口可按 `mode=interview|coding` 输出差异化建议
- [x] 至少一轮“作答 -> 画像更新 -> 下一题更针对”的闭环可复现

## E. 运维与观测
- [x] 可查看画像快照、画像事件、幂等状态
- [x] DLQ 重放支持单条与批量并返回统计
- [x] 幂等清理支持 memory/redis/all 且可审计

## F. 测试与验证
- [x] LearningProfileAgent 单测通过
- [x] Interview/Coding 画像事件上报测试通过
- [x] 画像推荐策略测试通过
- [x] 既有核心回归测试集通过
- [ ] `mvn test` 通过（正确 JDK 版本）

## G. 交付标准
- [x] 文档（spec/tasks/checklist/progress）与实现一致
- [x] 不引入破坏性 API 变更
- [x] 关键缺陷项在文档中有“修复前后对照”记录
