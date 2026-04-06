# 多模态 RAG 遗留项实现任务

> 来源：架构师实现计划
> 设计规范：`docs/superpowers/specs/2026-04-06-multimodal-rag-design.md`
> 执行顺序：Task 3 → Task 1 → Task 2（按依赖关系排列）

---

## Task 3: RAGService 构造器简化（最简单，先做）

### 目标

将 `RAGService` 的双构造器合并为单构造器。

### 文件

`src/main/java/com/example/interview/service/RAGService.java`

### 当前状态

有两个构造器：
- 第 77-82 行：15-param 向后兼容构造器（调用 16-param 构造器传 `null`）
- 第 84-102 行：16-param 构造器（带 `@Autowired` 和 `@Nullable ImageService`）

### 修复

1. **删除** 第 77-82 行的 15-param 构造器（整个方法体）
2. **保留** 第 84-102 行的 16-param 构造器
3. **移除** 保留构造器上的 `@org.springframework.beans.factory.annotation.Autowired` 注解（单构造器 Spring 自动识别，无需注解）
4. **保留** `ImageService` 参数的 `@org.springframework.lang.Nullable` 注解

改后构造器签名如下（注意无 `@Autowired`）：
```java
public RAGService(RoutingChatService routingChatService, VectorStore vectorStore, LexicalIndexService lexicalIndexService, WebSearchTool webSearchTool, RAGObservabilityService observabilityService, AgentSkillService agentSkillService, PromptTemplateService promptTemplateService, PromptManager promptManager, @org.springframework.beans.factory.annotation.Qualifier("ragRetrieveExecutor") java.util.concurrent.Executor ragRetrieveExecutor, com.example.interview.graph.TechConceptRepository techConceptRepository, ObservabilitySwitchProperties observabilitySwitchProperties, RetrievalTokenizerService retrievalTokenizerService, RagRetrievalProperties ragRetrievalProperties, ParentChildRetrievalProperties parentChildRetrievalProperties, ParentChildIndexService parentChildIndexService, @org.springframework.lang.Nullable ImageService imageService) {
```

### 验证

```bash
mvn -q compile
```

---

## Task 1: Milvus `interview_images` Collection 正式接入

### 目标

将 `ImageIndexService` 的内存 `ConcurrentHashMap` 替换为 Milvus `interview_images` collection，支持双向量字段 ANN 搜索。

### 背景

- Spring AI 的 `MilvusVectorStore` 只支持单向量字段，**不能用**
- 必须用 Milvus Java SDK (`io.milvus.client.MilvusServiceClient`) 直接操作
- Spring AI 自动装配会创建 `MilvusServiceClient` bean（连接 localhost:19530），可直接注入
- 现有 `interview_notes` collection 的配置参考：`application.yml` 的 `spring.ai.vectorstore.milvus` 段

### 1.1 新建 `ImageVectorStoreConfig.java`

文件位置：`src/main/java/com/example/interview/config/ImageVectorStoreConfig.java`

```java
package com.example.interview.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.IndexType;
import io.milvus.param.MetricType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageVectorStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(ImageVectorStoreConfig.class);

    private final MilvusServiceClient milvusClient;
    private final String collectionName;

    public ImageVectorStoreConfig(
            MilvusServiceClient milvusClient,
            @Value("${app.multimodal.milvus.image-collection:interview_images}") String collectionName) {
        this.milvusClient = milvusClient;
        this.collectionName = collectionName;
    }

    @PostConstruct
    public void initializeImageCollection() {
        try {
            // 检查 collection 是否存在
            R<Boolean> hasCollection = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(collectionName).build()
            );
            if (hasCollection.getData() != null && hasCollection.getData()) {
                logger.info("Milvus collection '{}' already exists, loading...", collectionName);
                loadCollection();
                return;
            }

            // 创建 collection schema
            FieldType imageIdField = FieldType.newBuilder()
                    .withName("image_id")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(128)
                    .withPrimaryKey(true)
                    .build();

            FieldType textEmbeddingField = FieldType.newBuilder()
                    .withName("text_embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(2048)
                    .build();

            FieldType visualEmbeddingField = FieldType.newBuilder()
                    .withName("visual_embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(512)
                    .build();

            FieldType metadataField = FieldType.newBuilder()
                    .withName("metadata")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(4096)
                    .build();

            CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                    .addFieldType(imageIdField)
                    .addFieldType(textEmbeddingField)
                    .addFieldType(visualEmbeddingField)
                    .addFieldType(metadataField)
                    .build();

            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withSchema(schema)
                    .build();

            R<RpcStatus> createResult = milvusClient.createCollection(createParam);
            if (createResult.getException() != null) {
                logger.error("Failed to create collection '{}': {}", collectionName, createResult.getException().getMessage());
                return;
            }
            logger.info("Created Milvus collection '{}'", collectionName);

            // 创建 text_embedding 索引
            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("text_embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\": 128}")
                    .build());
            logger.info("Created IVF_FLAT index on text_embedding");

            // 创建 visual_embedding 索引
            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("visual_embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\": 128}")
                    .build());
            logger.info("Created IVF_FLAT index on visual_embedding");

            loadCollection();

        } catch (Exception e) {
            logger.error("Failed to initialize image vector store: {}", e.getMessage(), e);
        }
    }

    private void loadCollection() {
        milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build()
        );
        logger.info("Loaded Milvus collection '{}' into memory", collectionName);
    }
}
```

**注意**：上面代码基于 Milvus SDK v2.3/2.4 API。如果编译时发现 API 差异（如 `CollectionSchemaParam` 不存在），需要根据实际 SDK 版本调整。可以参考 `spring-ai-starter-vector-store-milvus` 引入的具体 SDK 版本。

确认 SDK 版本的方法：
```bash
mvn dependency:tree | grep milvus
```

### 1.2 重写 `ImageIndexService.java`

文件位置：`src/main/java/com/example/interview/service/ImageIndexService.java`

**完全重写此文件**。核心要求：

```java
package com.example.interview.service;

import com.example.interview.entity.ImageMetadataDO;
import com.example.interview.mapper.ImageMetadataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ImageIndexService {

    private static final Logger logger = LoggerFactory.getLogger(ImageIndexService.class);

    private final MilvusServiceClient milvusClient;
    private final ImageMetadataMapper imageMetadataMapper;
    private final ImageEmbeddingService imageEmbeddingService;
    private final ObjectMapper objectMapper;
    private final String collectionName;

    public ImageIndexService(
            MilvusServiceClient milvusClient,
            ImageMetadataMapper imageMetadataMapper,
            ImageEmbeddingService imageEmbeddingService,
            @Value("${app.multimodal.milvus.image-collection:interview_images}") String collectionName) {
        this.milvusClient = milvusClient;
        this.imageMetadataMapper = imageMetadataMapper;
        this.imageEmbeddingService = imageEmbeddingService;
        this.objectMapper = new ObjectMapper();
        this.collectionName = collectionName;
    }

    /**
     * 将图片向量写入 Milvus collection。
     * 同时更新 MySQL t_image_metadata 的 text_vector_id 和 visual_vector_id。
     */
    public void indexImage(ImageMetadataDO metadata, java.nio.file.Path imagePath) {
        // 1. 生成 embedding
        // 2. 构建 InsertParam（4 个 field: image_id, text_embedding, visual_embedding, metadata JSON）
        //    - text_embedding: List<Float> 长度 2048
        //    - visual_embedding: List<Float> 长度 512（如果 disabled 则填零向量）
        //    - metadata: JSON 字符串 {"image_name": "...", "summary_snippet": "前50字..."}
        // 3. 调用 milvusClient.insert()
        // 4. 更新 MySQL metadata 的 textVectorId 和 visualVectorId
        //
        // 注意事项：
        //   - Milvus insert 的向量必须是 List<Float>，不是 List<Double>
        //   - 需要将 ImageEmbeddingService 返回的 List<Double> 转为 List<Float>
        //   - visual_embedding 为空时填 512 维零向量（Milvus 不允许 null 向量字段）
    }

    /**
     * 在 Milvus 中搜索相关图片。
     *
     * @param query 查询文本
     * @param topK  最大返回数量
     * @param visualIntent 是否包含视觉意图（如果为 true 且视觉 embedding 启用，额外搜 visual_embedding 字段）
     * @return 排序后的 ImageHit 列表
     */
    public List<ImageHit> search(String query, int topK, boolean visualIntent) {
        // 1. 生成 query 的 text embedding
        // 2. 构建 SearchParam:
        //    - collectionName
        //    - vectorFieldName: "text_embedding"
        //    - vectors: List<List<Float>> 包含 query embedding
        //    - topK
        //    - metricType: COSINE
        //    - params: {"nprobe": 16}
        //    - outFields: ["image_id", "metadata"]
        // 3. 调用 milvusClient.search()
        // 4. 解析 SearchResultsWrapper 获取 image_id 和 score
        // 5. 如果 visualIntent && imageEmbeddingService.isVisualEmbeddingEnabled():
        //    - 额外执行一次 search on "visual_embedding" 字段
        //    - 合并两路结果，同一 image_id 取最高分
        // 6. 返回 List<ImageHit>
    }

    public record ImageHit(String imageId, String imageName, double score, String retrieveChannel) {}
}
```

**关键实现细节**：

1. **Insert 时向量类型转换**：`ImageEmbeddingService` 返回 `List<Double>`，Milvus 需要 `List<Float>`。需要做转换：
   ```java
   List<Float> floatVector = doubleVector.stream().map(Double::floatValue).toList();
   ```

2. **Visual embedding 为空时**：当 `visual-embedding.enabled=false`，`ImageEmbeddingService.embed()` 返回空 `visualEmbedding`。但 Milvus collection schema 要求 `visual_embedding` 字段不能为空，必须填 512 维零向量。

3. **Search 结果解析**：使用 `SearchResultsWrapper` 从 Milvus search 结果中提取 `image_id`、score。metadata JSON 中存有 `image_name`，用于构造 `ImageHit`。

4. **Upsert 逻辑**：如果同一 `image_id` 已存在，Milvus 的 `insert()` 在有主键时会自动做 upsert（Milvus v2.3+）。确认项目使用的 Milvus v2.4.5 支持此行为。

### 1.3 application.yml 更新

在 `app.multimodal` 段下添加：

```yaml
app:
  multimodal:
    milvus:
      image-collection: ${APP_MULTIMODAL_MILVUS_IMAGE_COLLECTION:interview_images}
```

---

## Task 2: CLIP ViT-B/32 Python 微服务 + Java 集成

### 目标

创建 CLIP embedding 微服务（Python FastAPI），Java 侧通过 HTTP 调用获取视觉向量。

### 2.1 新建 `clip-service/` 目录

#### `clip-service/requirements.txt`

```
fastapi>=0.104.0
uvicorn>=0.24.0
open-clip-torch>=2.24.0
torch>=2.0.0
Pillow>=10.0.0
python-multipart>=0.0.6
```

#### `clip-service/Dockerfile`

```dockerfile
FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    -i https://pypi.tuna.tsinghua.edu.cn/simple \
    --trusted-host pypi.tuna.tsinghua.edu.cn \
    --proxy ""

COPY app.py .

# 预下载模型（构建阶段缓存，避免运行时下载）
RUN python -c "import open_clip; open_clip.create_model_and_transforms('ViT-B-32', pretrained='laion2b_s34b_b79k')"

EXPOSE 8200
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8200"]
```

#### `clip-service/app.py`

```python
"""
CLIP ViT-B/32 Embedding Service
提供图片和文本的 CLIP 向量化接口
"""
import base64
import io
import logging
from contextlib import asynccontextmanager

import open_clip
import torch
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel

logger = logging.getLogger("clip-service")

# 全局模型和预处理器
model = None
preprocess = None
tokenizer = None
device = "cpu"


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用启动时加载 CLIP 模型"""
    global model, preprocess, tokenizer
    logger.info("Loading CLIP ViT-B-32 model...")
    model, _, preprocess = open_clip.create_model_and_transforms(
        "ViT-B-32", pretrained="laion2b_s34b_b79k", device=device
    )
    tokenizer = open_clip.get_tokenizer("ViT-B-32")
    model.eval()
    logger.info("CLIP model loaded successfully")
    yield


app = FastAPI(title="CLIP Embedding Service", lifespan=lifespan)


class ImageEmbedRequest(BaseModel):
    image_base64: str
    mime_type: str = "image/png"


class TextEmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    embedding: list[float]
    dimension: int


@app.post("/embed", response_model=EmbedResponse)
async def embed_image(request: ImageEmbedRequest):
    """将图片转为 512 维 CLIP 视觉向量"""
    try:
        image_bytes = base64.b64decode(request.image_base64)
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        image_tensor = preprocess(image).unsqueeze(0).to(device)

        with torch.no_grad():
            embedding = model.encode_image(image_tensor)
            embedding = embedding / embedding.norm(dim=-1, keepdim=True)

        vector = embedding.squeeze().cpu().tolist()
        return EmbedResponse(embedding=vector, dimension=len(vector))
    except Exception as e:
        logger.error(f"Image embedding failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/embed-text", response_model=EmbedResponse)
async def embed_text(request: TextEmbedRequest):
    """将文本转为 512 维 CLIP 文本向量"""
    try:
        text_tokens = tokenizer([request.text]).to(device)

        with torch.no_grad():
            embedding = model.encode_text(text_tokens)
            embedding = embedding / embedding.norm(dim=-1, keepdim=True)

        vector = embedding.squeeze().cpu().tolist()
        return EmbedResponse(embedding=vector, dimension=len(vector))
    except Exception as e:
        logger.error(f"Text embedding failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    return {
        "status": "ok" if model is not None else "loading",
        "model": "ViT-B-32",
        "pretrained": "laion2b_s34b_b79k",
        "dimension": 512,
    }
```

### 2.2 docker-compose.yml 添加服务

在 `rocketmq-dashboard` 服务后、`ragas-eval` 服务前添加：

```yaml
  clip-embedding:
    build:
      context: ./clip-service
      dockerfile: Dockerfile
    container_name: clip-embedding
    ports:
      - "8200:8200"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8200/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    restart: unless-stopped
```

### 2.3 修改 `ImageEmbeddingService.java`

文件：`src/main/java/com/example/interview/service/ImageEmbeddingService.java`

改动要点：

1. **注入 CLIP 服务 URL**：
   ```java
   @Value("${app.multimodal.clip.service-url:http://localhost:8200}")
   private String clipServiceUrl;
   ```

2. **注入 RestTemplate**（可以在构造器里 `new RestTemplate()` 或注入 Bean）

3. **重写 `embedVisual(Path imagePath)`**：
   ```java
   private List<Double> embedVisual(Path imagePath) {
       if (!visualEmbeddingEnabled || imagePath == null) {
           return List.of();
       }
       try {
           // 1. 读取图片文件为 base64
           byte[] imageBytes = Files.readAllBytes(imagePath);
           String base64Image = Base64.getEncoder().encodeToString(imageBytes);

           // 2. 检测 MIME 类型
           String mimeType = Files.probeContentType(imagePath);
           if (mimeType == null) mimeType = "image/png";

           // 3. 调用 CLIP 服务
           // POST {clipServiceUrl}/embed
           // Body: {"image_base64": "...", "mime_type": "..."}
           // Response: {"embedding": [...], "dimension": 512}

           // 4. 解析响应中的 embedding 为 List<Double>
           // 5. 返回向量
       } catch (Exception e) {
           logger.warn("CLIP embedding failed for {}, returning empty: {}", imagePath, e.getMessage());
           return List.of();
       }
   }
   ```

4. **删除 `zeroVector()` 方法**（不再需要）

5. **HTTP 调用细节**：
   - 使用 `RestTemplate.postForObject(clipServiceUrl + "/embed", request, ClipResponse.class)`
   - 定义内部类或 record 来接收响应：
     ```java
     private record ClipRequest(String image_base64, String mime_type) {}
     private record ClipResponse(List<Double> embedding, int dimension) {}
     ```
   - 超时设置：连接 5s，读取 30s（CLIP 推理可能较慢）

### 2.4 application.yml 更新

在 `app.multimodal` 段下添加：

```yaml
app:
  multimodal:
    clip:
      service-url: ${APP_MULTIMODAL_CLIP_SERVICE_URL:http://localhost:8200}
```

`app.image.visual-embedding.enabled` **保持 `false`** 不变（用户需手动启用）。

---

## 验证要求

完成所有修改后，执行：

```bash
# 1. 确认 Milvus SDK 版本
mvn dependency:tree | grep milvus

# 2. 编译验证
mvn -q compile

# 3. 前端构建（不应受影响）
cd frontend && npm run build
```

将修复结果写入 `codex-result.md`，对每个 Task 列出：
- 完成状态
- 变更的文件
- 关键实现决策（如 SDK 版本适配）
- 遇到的问题（如果有）
