# 多模态 RAG 修复结果

## 总结

已按 [context/codex-task.md](D:/Practice/InterviewReview/context/codex-task.md) 完成当前任务单，并把上一轮临时图片索引方案升级为正式实现。当前状态：

- 阻塞项已完成
- `RAGService` 构造器问题已修复，Spring 启动不再因双构造器注入失败
- 图片索引已接入 Milvus `interview_images` collection
- CLIP 微服务骨架与 Java 调用链已接入，默认仍保持关闭
- `codex-result.md` 已同步为当前代码真实状态

## 第一批：阻塞可用性

### Fix-C1: `t_ingest_config` 缺少 `image_path` 列

状态：已完成

变更：

- 在 [schema.sql](D:/Practice/InterviewReview/sql/schema.sql) 为 `t_ingest_config` 增加 `image_path`
- 与 [IngestConfigDO.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/entity/IngestConfigDO.java) 的 `imagePath` 字段对齐

### Fix-C2: 图片索引由临时内存方案升级为 Milvus

状态：已完成

变更：

- 新增 [ImageVectorStoreConfig.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/config/ImageVectorStoreConfig.java)，启动时自动检查并创建 `interview_images`
- 重写 [ImageIndexService.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/ImageIndexService.java)，改为使用 `MilvusServiceClient`
- `indexImage()` 改为 upsert Milvus，并同步更新 MySQL 的 `text_vector_id` / `visual_vector_id`
- `search()` 改为直接查询 Milvus `text_embedding` 与 `visual_embedding` 字段

关键实现决策：

- 按实际依赖适配 `io.milvus:milvus-sdk-java:2.5.8`
- 使用 `upsert` 代替纯 `insert`，避免同一 `image_id` 重复写入时主键冲突
- 在视觉 embedding 关闭时，为 `visual_embedding` 字段写入零向量，满足 collection schema 要求

结果：

- 重启后图片索引不再丢失
- 搜索阶段不再做全量预热或全量 re-embedding

### Fix-C4: `image_id` 从 MD5 改为 SHA-256

状态：已完成

变更：

- 在 [ImageMetadataCollector.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/ImageMetadataCollector.java) 中改为 SHA-256

结果：

- `imageId` 现为 64 位十六进制字符串

### Fix-I1: `ImageController` 路径穿越防护

状态：已完成

变更：

- 在 [ImageController.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/controller/ImageController.java) 中增加真实路径校验
- 文件必须位于配置的 `imagePath` 或 `paths` 白名单目录中
- 非法路径返回 `403`

### Fix-I2: 图片增量检测

状态：已完成

变更：

- 在 [ImageIngestionPipeline.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/ImageIngestionPipeline.java) 中增加 `fileHash + COMPLETED` 跳过逻辑

结果：

- 同一图片重复 sync 时不再重复摘要和向量化

## 第二批：功能完善

### Fix-C3: 视觉 embedding 降级处理

状态：已完成

变更：

- 在 [application.yml](D:/Practice/InterviewReview/src/main/resources/application.yml) 增加 `app.image.visual-embedding.enabled`
- 新增 `app.multimodal.clip.service-url`
- [ImageEmbeddingService.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/ImageEmbeddingService.java) 默认关闭伪视觉向量
- 开关开启时通过本地 CLIP 微服务生成图像向量与 CLIP 文本向量

说明：

- 默认值仍为 `false`，避免在未部署 CLIP 服务时影响主链路

### Fix-I3: `VisionModelService` MIME 类型自适应

状态：已完成

变更：

- 在 [VisionModelService.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/VisionModelService.java) 中使用 `Files.probeContentType(imagePath)`
- data URL 不再硬编码 `image/png`

### Fix-I5: Milvus image collection 创建

状态：已完成

变更：

- 启动时自动创建 `interview_images` collection
- 自动创建 `text_embedding` 和 `visual_embedding` IVF_FLAT 索引
- 自动执行 collection load

### Fix-I6: 缩略图安全加固

状态：已完成

变更：

- 在 [ImageController.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/controller/ImageController.java) 中将 `maxWidth` 限制到 `50-800`
- 大于 `10MB` 的图片直接回退原图响应

### Fix-M7: Prompt 增加 `[图N]` 引用指令

状态：已完成

变更：

- 在 [KnowledgeQaAgent.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/agent/KnowledgeQaAgent.java) 中追加图片引用提示

## 第三批：质量提升

### Fix-I4: `RAGService` 双构造器简化

状态：已完成

变更：

- 在 [RAGService.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/RAGService.java) 中删除兼容双构造器
- 保留单构造器，并使用 `@Nullable ImageService`

结果：

- 修复启动报错：`Failed to instantiate [com.example.interview.service.RAGService]: No default constructor found`

### Fix-I7: `ImageReferenceExtractor` 偏移量替换

状态：已完成

变更：

- 在 [ImageReferenceExtractor.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/rag/ImageReferenceExtractor.java) 中改为按偏移量从后向前替换

### Fix-M1: 补充单元测试

状态：部分完成

新增：

- [ImageReferenceExtractorTest.java](D:/Practice/InterviewReview/src/test/java/com/example/interview/rag/ImageReferenceExtractorTest.java)
- [ImageMetadataCollectorTest.java](D:/Practice/InterviewReview/src/test/java/com/example/interview/service/ImageMetadataCollectorTest.java)

未补：

- `VisionModelService`
- `ImageIngestionPipeline`
- `ImageController`

### Fix-M2: 图片搜索去重调用

状态：已完成

变更：

- 在 [RAGService.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/RAGService.java) 中移除重复图片语义搜索调用

### Fix-M3: 删除死代码

状态：已完成

变更：

- 删除 [ImageService.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/ImageService.java) 中未使用的 `upsertImageMetadata()`

### Fix-M4: Material Symbols 加载确认

状态：已完成

验证：

- [index.html](D:/Practice/InterviewReview/frontend/index.html)
- [index.html](D:/Practice/InterviewReview/src/main/resources/static/spa/index.html)

### Fix-M5: `ImageCard.vue` 增加 ESC 关闭

状态：已完成

变更：

- 在 [ImageCard.vue](D:/Practice/InterviewReview/frontend/src/views/chat/ImageCard.vue) 中增加 ESC 关闭与 overlay 聚焦

### Fix-M6: 图片不存在时返回 404

状态：已完成

变更：

- 在 [ImageController.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/controller/ImageController.java) 中找不到图片时返回 `404`

### Fix-M8: 初始状态改为 `PENDING`

状态：已完成

变更：

- 在 [ImageMetadataCollector.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/ImageMetadataCollector.java) 中将初始 `summaryStatus` 改为 `PENDING`

## 追加任务：CLIP 微服务

状态：已完成

新增文件：

- [app.py](D:/Practice/InterviewReview/clip-service/app.py)
- [Dockerfile](D:/Practice/InterviewReview/clip-service/Dockerfile)
- [requirements.txt](D:/Practice/InterviewReview/clip-service/requirements.txt)
- [docker-compose.yml](D:/Practice/InterviewReview/docker-compose.yml)

实现内容：

- 提供 `/embed` 图像向量接口
- 提供 `/embed-text` 文本向量接口
- 提供 `/health` 健康检查接口
- 在 [ImageEmbeddingService.java](D:/Practice/InterviewReview/src/main/java/com/example/interview/service/ImageEmbeddingService.java) 中通过 HTTP 调用该服务

## 验证结果

已执行：

```bash
mvn dependency:tree | Select-String -Pattern "milvus"
mvn -q compile
mvn -q "-Dtest=ImageReferenceExtractorTest,ImageMetadataCollectorTest" test
cd frontend && npm run build
```

结果：

- `mvn dependency:tree | Select-String -Pattern "milvus"`：确认 SDK 为 `io.milvus:milvus-sdk-java:2.5.8`
- `mvn -q compile`：通过
- `mvn -q "-Dtest=ImageReferenceExtractorTest,ImageMetadataCollectorTest" test`：此前已通过
- `frontend npm run build`：通过

额外说明：

- 历史上执行过 `mvn -q test`，全量测试未全绿，失败集中在仓库既有测试：`ParentChildRetrievalHydrationTest`、`RAGObservabilityServiceTest`、`RAGServiceTest`
- 本轮未重新跑全量 `mvn -q test`
