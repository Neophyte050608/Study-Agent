# 多模态图文 RAG 系统设计方案

> 方案：图片独立索引 + 双路召回融合（方案 A）
> 日期：2026-04-06
> 状态：已批准

---

## 一、目标与背景

### 1.1 现状

- 知识问答仅支持纯文本检索，Obsidian 笔记中的 `![[image.png]]` 图片引用在入库时被当作普通文本处理
- 知识入库流水线：`NoteLoader` → `ObsidianKnowledgeExtractor` → `DocumentSplitter` → 三通道存储（Milvus 向量 + MySQL 词法 + Neo4j 图谱）
- 混合检索：三通道并行（意图定向词法、全局向量、GraphRAG），RRF 融合 → Parent-Child 回填 → 重排 → Web Fallback
- 向量库：Milvus，collection `interview_notes`，embedding 维度 2048（智谱 embedding-3）
- 前端 `t_chat_message` 已预留 `content_type` 字段（text/image/file）

### 1.2 目标

- 在知识构建阶段，解析文档中的图片引用，通过视觉模型生成结构化语义摘要
- 图片摘要回填到对应文本上下文中，形成图文关联的知识块
- 文本和图片分别向量化，但在语义层面保持关联
- 检索时同步召回相关文本和配图，前端图文混排展示

### 1.3 约束

- 数据库可直接修改表结构，无需数据迁移（现有数据为模拟数据，支持全量重建）
- 图片存储在 Obsidian 统一附件目录（如 `attachments/`），以 PNG 为主
- 图片类型：技术架构图、代码截图等，附件目录中混有未被笔记引用的无关图片
- 视觉模型：智谱 GLM-4V（后续支持模型配置切换）
- 向量策略：双模态 embedding（文本摘要向量 + 视觉向量）
- 第一期仅支持 Web 前端展示，IM 渠道图片推送后续扩展
- 图片访问：本地文件 + HTTP 接口，图床上传后续扩展

---

## 二、数据模型

### 2.1 新增 MySQL 表

```sql
-- 图片元数据表
CREATE TABLE IF NOT EXISTS `t_image_metadata` (
    `id`               BIGINT NOT NULL AUTO_INCREMENT,
    `image_id`         VARCHAR(128) NOT NULL COMMENT '图片唯一ID (SHA256(路径+文件名))',
    `image_name`       VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_path`        VARCHAR(512) NOT NULL COMMENT '文件绝对路径',
    `relative_path`    VARCHAR(512) COMMENT '相对于 vault 的路径',
    `summary_text`     TEXT COMMENT 'VLM 生成的语义摘要',
    `summary_status`   VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/COMPLETED/FAILED',
    `mime_type`        VARCHAR(64) COMMENT 'image/png, image/jpeg 等',
    `width`            INT COMMENT '图片宽度',
    `height`           INT COMMENT '图片高度',
    `file_size`        BIGINT COMMENT '文件大小(字节)',
    `file_hash`        VARCHAR(128) COMMENT '文件内容 MD5，用于增量检测',
    `text_vector_id`   VARCHAR(128) COMMENT 'Milvus 中文本摘要向量 ID',
    `visual_vector_id` VARCHAR(128) COMMENT 'Milvus 中视觉向量 ID',
    `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_image_id` (`image_id`),
    KEY `idx_image_name` (`image_name`),
    KEY `idx_file_hash` (`file_hash`),
    KEY `idx_summary_status` (`summary_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图片元数据表';

-- 文本块与图片关联表 (多对多)
CREATE TABLE IF NOT EXISTS `t_text_image_relation` (
    `id`               BIGINT NOT NULL AUTO_INCREMENT,
    `text_chunk_id`    VARCHAR(128) NOT NULL COMMENT '文本块ID (child_id)',
    `parent_doc_id`    VARCHAR(128) NOT NULL COMMENT '父文档ID (parent_id)',
    `image_id`         VARCHAR(128) NOT NULL COMMENT '图片ID',
    `position_in_text` INT COMMENT '图片占位符在文本中的字符偏移',
    `ref_syntax`       VARCHAR(512) COMMENT '原始引用语法 ![[xxx.png]]',
    `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_image` (`text_chunk_id`, `image_id`),
    KEY `idx_parent_doc` (`parent_doc_id`),
    KEY `idx_image` (`image_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文本图片关联表';
```

### 2.2 Milvus 新增 Collection

```
Collection: interview_images
├── image_id          VARCHAR(128)       — 主键，关联 t_image_metadata
├── text_embedding    FLOAT_VECTOR(2048) — 智谱 embedding-3 对摘要文本的向量
├── visual_embedding  FLOAT_VECTOR(512)  — CLIP ViT-B/32 视觉向量
└── metadata          JSON               — {image_name, file_path, summary_snippet}
```

- `text_embedding` 维度 2048，与现有 `interview_notes` 一致，复用智谱 embedding-3
- `visual_embedding` 维度 512（CLIP ViT-B/32），先按此设计，后续可升级 ViT-L/14 (768)
- 两个向量字段分别建 IVF_FLAT 索引

### 2.3 现有表改动

- `t_rag_parent`：新增 `image_refs JSON` 字段，存储该文档引用的图片 ID 列表（冗余加速查询）
- `t_chat_message`：`content_type` 已预留，无需改动。图文回复用 `content_type='rich'`，content 为结构化 JSON

---

## 三、知识入库流程

### 3.1 管线扩展

```
现有流程：
NoteLoader → ObsidianKnowledgeExtractor → DocumentSplitter →
  ├─ Milvus (文本向量)
  ├─ MySQL (词法索引)
  └─ Neo4j (图谱)

扩展后：
NoteLoader → ObsidianKnowledgeExtractor (提取图片引用) → DocumentSplitter →
  ├─ 原有三通道 (文本)
  └─ ImageIngestionPipeline (新增)
       ├─ 图片文件扫描与去重
       ├─ VLM 摘要生成 (GLM-4V)
       ├─ 双模态向量化 (智谱 embedding-3 + CLIP)
       ├─ Milvus interview_images 存储
       └─ 关联关系写入 t_text_image_relation
```

### 3.2 图片入库方式

图片入库与笔记入库走同一个本地路径扫描机制：

```
配置 vault 路径 + 附件目录路径 →
  ├─ NoteLoader 扫描 .md 文件 → 文本入库（现有）
  └─ ImageLoader 扫描附件目录 .png 文件 → 图片入库（新增）
     只处理被 .md 笔记中 ![[xxx.png]] 引用的图片
     未被引用的图片直接跳过
```

配置方式：

```yaml
app:
  ingestion:
    vault-path: D:/Obsidian/MyVault/notes
    image-path: D:/Obsidian/MyVault/attachments   # 新增
```

入库流程：

1. 扫描 vault-path 下所有 .md → 提取文本 + 图片引用列表
2. 汇总所有被引用的图片名 → 在 image-path 下查找对应 .png 文件
3. 文本走现有管线，图片走 `ImageIngestionPipeline`
4. 写入 `t_text_image_relation` 关联

### 3.3 核心组件

#### ImageReferenceExtractor（图片引用提取器）

- 输入：Markdown 文本
- 输出：`List<ImageReference>`（图片名、位置偏移、引用语法）
- 正则匹配 `![[image.png]]` 和 `![](path/to/image.png)`
- 解析相对路径，转换为绝对路径

#### ImageMetadataCollector（图片元数据收集器）

- 输入：图片文件路径
- 输出：`ImageMetadata`（宽高、大小、MIME、文件哈希）
- 计算 MD5 哈希（增量检测）
- 生成唯一 `image_id = SHA256(file_path)`

#### VisionModelService（视觉模型服务）

- 输入：图片文件路径
- 输出：`ImageSummary`（结构化摘要文本）
- 调用智谱 GLM-4V API
- Prompt 模板：

```
你是技术文档图片分析专家。请分析这张图片并生成结构化摘要：
1. 图片类型（架构图/代码截图/流程图/配置截图/其他）
2. 核心技术要素（提取关键词，如 Spring Boot, Redis, MySQL）
3. 图片描述（50-150字，重点描述技术细节）

输出格式：
{
  "type": "架构图",
  "keywords": ["Spring Boot", "Redis", "MySQL"],
  "description": "展示了基于 Spring Boot 的微服务架构..."
}
```

- 异常处理：失败时返回降级摘要 `"图片：{文件名}"`

#### ImageEmbeddingService（图片向量化服务）

- 输入：图片摘要文本 + 图片文件路径
- 输出：`ImageEmbedding`（文本向量 + 视觉向量）
- 文本向量：智谱 embedding-3 API 对摘要文本向量化
- 视觉向量：CLIP 模型（可选本地部署或 API）
- 支持 batch embedding

#### ImageIndexService（图片索引服务）

- 将图片元数据写入 `t_image_metadata`
- 将双模态向量写入 Milvus `interview_images` collection
- 将文本-图片关联写入 `t_text_image_relation`
- 更新 `t_rag_parent.image_refs` 冗余字段

### 3.4 增量更新策略

- 图片去重：通过 `file_hash` 判断图片是否变化
- 摘要更新：图片内容变化时，重新生成摘要和向量
- 孤立图片清理：定期扫描 `t_image_metadata`，删除不再被任何文本引用的图片记录

### 3.5 异步处理

图片处理耗时较长（VLM 调用 + 向量化），采用异步管线：

```java
@Async("imageIngestionExecutor")
public CompletableFuture<ImageIngestionResult> processImage(String imagePath) {
    // 1. 元数据收集 (快)
    // 2. VLM 摘要生成 (慢，3-5秒)
    // 3. 双模态向量化 (中等，1-2秒)
    // 4. 索引写入 (快)
}
```

- 入库时先写入 `summary_status='PENDING'`
- 异步任务完成后更新为 `COMPLETED`
- 前端可通过 `/api/images/status` 轮询进度

---

## 四、检索与融合排序

### 4.1 混合检索架构扩展

在现有 `retrieveHybridDocuments` 的三通道基础上增加图片检索通道：

```
现有三通道:
  ├─ 通道 A: 意图定向词法检索 (intent_directed)
  ├─ 通道 B: 全局向量检索 (global_vector)
  └─ 通道 C: GraphRAG 图谱检索 (graph_rag)

扩展为四通道:
  ├─ 通道 A: 意图定向词法检索
  ├─ 通道 B: 全局向量检索
  ├─ 通道 C: GraphRAG 图谱检索
  └─ 通道 D: 图片语义检索 (image_semantic) ← 新增
       ├─ D1: 文本摘要向量检索 (query → text_embedding)
       └─ D2: 视觉向量检索 (query → visual_embedding, 可选)
```

### 4.2 图片检索通道

```java
// 通道 D：图片语义检索
CompletableFuture<List<ImageHit>> imageFuture = CompletableFuture.supplyAsync(() -> {
    // D1: 用 query 的文本 embedding 在 interview_images.text_embedding 上检索
    List<ImageHit> textHits = imageIndexService.searchByTextEmbedding(queryEmbedding, topK);

    // D2 (可选): 如果 query 包含视觉相关描述词，走 visual_embedding 检索
    if (containsVisualIntent(query)) {
        List<ImageHit> visualHits = imageIndexService.searchByVisualEmbedding(queryEmbedding, topK);
        return mergeImageHits(textHits, visualHits);
    }
    return textHits;
}, ragRetrieveExecutor);
```

### 4.3 图文融合排序策略

```
四通道结果 → RRF 融合 (文本通道) → 图片通道关联注入 → 最终排序

具体流程:
1. 文本通道 A+B+C → 现有 RRF 融合 → textResults (Top-K)
2. 图片通道 D → imageResults (Top-M, M=3)
3. 关联注入:
   a. 对 textResults 中的每个文本块，查询 t_text_image_relation
      获取关联图片 → 作为 "附属图片" 挂载
   b. 对 imageResults 中的图片，如果未被 textResults 命中，
      检查是否有高分文本块引用它 → 如有则一并返回
4. 去重: 同一图片通过"附属"和"独立检索"两条路径命中，只保留一份
```

### 4.4 返回数据结构

```java
public record MultimodalKnowledgePacket(
    String retrievalQuery,
    List<Document> retrievedDocs,        // 文本块（现有）
    List<ImageResult> retrievedImages,   // 图片结果（新增）
    String context,                      // 纯文本上下文（给 LLM）
    String imageContext,                 // 图片描述上下文（给 LLM）
    String retrievalEvidence,
    boolean webFallbackUsed
) {}

public record ImageResult(
    String imageId,
    String imageName,
    String accessUrl,         // /api/images/{imageId}/file
    String summaryText,       // VLM 摘要
    String sourceChunkId,     // 关联的文本块 ID（可为空）
    double relevanceScore,
    String retrieveChannel    // "text_embedding" / "visual_embedding" / "text_associated"
) {}
```

### 4.5 LLM Prompt 图片上下文注入

```
检索上下文:
{text_context}

相关图片说明:
[图1] {summary_text_1} — 来源: {file_path_1}
[图2] {summary_text_2} — 来源: {file_path_2}

注意：你的回答可以引用上述图片，使用 [图N] 标记。
系统会自动将对应图片内联展示给用户。
```

LLM 回答中出现 `[图1]` 时，后端将其替换为实际图片标记，前端渲染。

### 4.6 降级策略

| 场景 | 处理方式 |
|------|----------|
| 图片检索通道超时 | 降级：仅返回文本结果，不阻塞 |
| VLM 摘要为空 | 降级：使用文件名作为摘要 |
| 图片文件不存在 | 过滤：不返回该图片，日志告警 |
| CLIP 模型不可用 | 降级：仅使用文本摘要向量检索 |

---

## 五、图片访问与前端展示

### 5.1 图片文件访问接口

```java
@RestController
@RequestMapping("/api/images")
public class ImageController {

    // 返回图片文件流（供前端 <img> 标签访问）
    @GetMapping("/{imageId}/file")
    public ResponseEntity<Resource> getImageFile(@PathVariable String imageId);

    // 返回图片元数据（摘要、宽高、关联文本块等）
    @GetMapping("/{imageId}/metadata")
    public ResponseEntity<ImageMetadataVO> getImageMetadata(@PathVariable String imageId);

    // 返回图片缩略图（宽度限制，减少传输量）
    @GetMapping("/{imageId}/thumbnail")
    public ResponseEntity<Resource> getThumbnail(
        @PathVariable String imageId,
        @RequestParam(defaultValue = "300") int maxWidth);

    // 批量查询图片元数据（检索结果一次性加载）
    @PostMapping("/batch-metadata")
    public ResponseEntity<List<ImageMetadataVO>> batchGetMetadata(
        @RequestBody List<String> imageIds);
}
```

### 5.2 SSE 流式回复图文混排

当前 `ChatStreamingService` 通过 SSE 返回纯文本流。改造为支持结构化事件：

```
现有 SSE 事件流:
  data: {"type": "text", "content": "Spring Boot 的网关配置..."}
  data: {"type": "done"}

改造后:
  data: {"type": "text", "content": "Spring Boot 的网关配置..."}
  data: {"type": "text", "content": "如下图所示："}
  data: {"type": "image", "imageId": "abc123", "url": "/api/images/abc123/file",
         "thumbnail": "/api/images/abc123/thumbnail?maxWidth=400",
         "summary": "Spring Cloud Gateway 路由配置示意图"}
  data: {"type": "text", "content": "\n如图所示，需要配置路由规则..."}
  data: {"type": "done", "images": [...]}
```

### 5.3 前端 ChatView 改造

```
消息渲染逻辑:
1. 收到 type="text" → 累加到 Markdown 渲染区
2. 收到 type="image" → 插入 <ImageCard> 组件:
   ├─ 缩略图懒加载 (thumbnail URL)
   ├─ 点击弹出大图 (file URL)
   ├─ 底部显示图片摘要 (summary)
   └─ 悬浮显示来源信息
3. 收到 type="done" → 渲染完成状态
```

### 5.4 消息持久化

`t_chat_message` 中 `content_type = 'rich'` 时，content 字段格式：

```json
{
  "text": "Spring Boot 的网关配置如下...\n如图所示...",
  "images": [
    {
      "imageId": "abc123",
      "position": 42,
      "summary": "Spring Cloud Gateway 路由配置示意图"
    }
  ]
}
```

---

## 六、分阶段实施规划

### Phase 1: 基础设施（数据模型 + 图片扫描）

- 新建 `t_image_metadata`, `t_text_image_relation` 表
- 修改 `t_rag_parent` 增加 `image_refs` 字段
- `ImageReferenceExtractor` 图片引用提取器
- `ImageMetadataCollector` 元数据收集器
- `ImageLoader` 本地图片扫描器
- 入库配置增加 `image-path`

### Phase 2: 图片理解（VLM + 向量化）

- `VisionModelService`（GLM-4V 摘要生成）
- `ImageEmbeddingService`（智谱 embedding-3 + CLIP）
- Milvus `interview_images` collection 创建
- `ImageIndexService`（索引写入）
- 异步处理线程池配置

### Phase 3: 检索融合（四通道混合检索）

- `RAGService.retrieveHybridDocuments` 增加图片通道
- `MultimodalKnowledgePacket` 数据结构
- 图文 RRF 融合排序
- LLM Prompt 图片上下文注入
- 降级策略实现

### Phase 4: 展示层（API + 前端）

- `ImageController` 图片文件/元数据接口
- SSE 流式事件扩展（type=image）
- `ChatView` 图文混排渲染
- `ImageCard` 组件
- `t_chat_message` rich 类型支持

---

## 七、技术约束

| 约束项 | 值 | 说明 |
|--------|-----|------|
| 图片格式 | PNG 优先，兼容 jpg/jpeg/gif/webp | 附件目录以 PNG 为主 |
| 图片摘要长度 | 50-200 字 | 平衡语义丰富度与 embedding 质量 |
| 文本 embedding 维度 | 2048 | 复用智谱 embedding-3 |
| 视觉 embedding 维度 | 512 | CLIP ViT-B/32 默认维度 |
| 图片检索 Top-K | 3 | 每次检索最多返回 3 张图片 |
| VLM 并发限制 | 3 | GLM-4V API 并发控制 |
| 图片缩略图宽度 | 400px | 前端 ImageCard 默认缩略图 |
| 异步线程池大小 | 4 | imageIngestionExecutor |

---

## 八、不在本期范围

- IM 渠道图片推送（飞书/QQ）
- 图床上传（后续扩展）
- 图片 OCR 文字提取（可作为 VLM 摘要的补充）
- 用户上传图片提问（图片输入检索）
