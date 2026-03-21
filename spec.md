# 面试复盘助手 (InterviewReview) 项目规格说明书

## 1. 项目概览
**项目名称**: InterviewReview
**目标**: 一个基于 Java 的面试练习工具，能够进行模拟面试，记录问答内容，并通过分析用户的回答与本地 Obsidian 笔记及互联网资源，生成结构化的复盘报告。
**目标用户**: 求职者，准备技术面试的开发者。

## 2. 核心能力

| 能力维度 | 具体要求 |
| :--- | :--- |
| **数据来源** | 1. **本地 Obsidian 笔记**: 解析并索引指定本地目录下的 Markdown 文件。<br>2. **用户简历 (PDF)**: 解析用户上传的 PDF 简历，提取技能栈和项目经历作为提问依据。<br>3. **联网查询**: 使用网络搜索补充本地知识库，获取最新信息。 |
| **数据存储** | **Milvus 向量数据库**: 使用 Docker 部署 Milvus (推荐版本 **v2.4.0+**)，支持高性能向量检索和持久化。支持**增量更新**（通过元数据管理文件版本）。 |
| **交互方式** | **语音对话 + Web 界面**: <br>1. **TTS**: AI 语音朗读问题。<br>2. **倒计时**: 读题后给予 15s 思考时间（支持按键提前结束）。<br>3. **STT**: 用户语音回答，自动转文字。<br>4. **UI**: 展示当前问题、倒计时、实时评分和鼓励文案。 |
| **技术栈** | **Java 21**, **Spring Boot 3.3+**, **Spring AI 1.0.0**, **Milvus**。前端采用 **HTML5 + JS** (利用浏览器原生 STT/TTS API 或集成 OpenAI Audio API)。 |
| **核心流程** | 1. **面试**: AI 提问 (TTS) -> 思考 15s -> 用户语音回答 (STT)。<br>2. **高级 RAG 流程**: 意图识别 -> 改写 -> 检索 -> 重排 -> 生成 -> 后处理。<br>3. **实时反馈**: AI 打分 -> UI 展示动态特效。<br>4. **报告**: 生成 Markdown 复盘报告。 |

## 3. 架构设计

### 3.1. 高层架构
- **接口层**: **REST API** + **Web UI**。
  - `InterviewController`: 提供 `/start`, `/answer` 等接口。
  - 前端: 单页应用 (SPA)，负责语音交互 (Web Speech API) 和状态展示。
- **应用层**:
  - `InterviewService`: 管理面试会话状态。
  - `AnalysisService`: 处理面试后的分析。
  - `RAGService`: 负责**高级 RAG 流程**。
  - `IngestionService`: 负责文档的摄入和增量同步。
- **基础设施层**:
  - **Spring AI Chat Client**: 与 LLM (如 OpenAI, DeepSeek) 交互。
  - **Vector Store**: **Milvus** (Docker 部署，版本 v2.4.x)。

### 3.2. 数据流
1. **初始化 / 同步**:
   - (保持不变) ...
2. **面试会话 (语音 + RAG)**:
   - **AI 提问**: 后端生成问题 -> 前端 TTS 朗读。
   - **思考**: 前端倒计时 15s (可跳过)。
   - **回答**: 用户说话 -> 前端 STT 转文字 -> 发送 `/answer` 请求。
   - **RAG 处理**: 后端执行 (意图->改写->检索->重排->生成->后处理)。
   - **反馈**: 后端返回 (分数, 评价, 下一题) -> 前端展示分数/文案 -> TTS 朗读评价和下一题。
   - Chat Memory 记录历史。
3. **复盘与分析**:
   - 会话结束时触发。
   - 对于每一组问答:
     - 在向量存储中查询相关笔记。
     - 如果置信度低或缺少笔记，触发 `WebSearchTool`。
     - LLM 对比用户回答 vs (笔记 + 网络信息)。
   - 生成 "差距分析"。
4. **报告生成**:
   - 将分析结果格式化为 Markdown 报告 (例如 `interview_report_20231027.md`)。

## 4. 技术规格

### 4.1. 依赖
- `spring-boot-starter-web` (用于潜在的 API)
- `spring-ai-openai-spring-boot-starter` (或 DeepSeek/兼容组件)
- `spring-ai-milvus-store-spring-boot-starter` (Milvus 向量存储)
- `spring-ai-tavily-search-spring-boot-starter` (或通用的搜索函数调用)
- `spring-ai-pdf-document-reader` (用于简历解析)
- `lombok`

### 4.2. 类结构 (建议)
- `com.example.interview.core`
  - `InterviewSession`:以此保存聊天记录和状态。
  - `Question`: 问答模型。
- `com.example.interview.rag`
  - `NoteLoader`: 从磁盘读取文件。
  - `VectorStoreConfig`: 配置嵌入模型和存储。
- `com.example.interview.service`
  - `InterviewAgent`: 管理对话流程。
  - `ReviewAgent`: 执行分析。
- `com.example.interview.tools`
  - `SearchTool`: 网络搜索接口。

## 5. 用户交互 (CLI 场景)
```text
> 欢迎使用 InterviewReview!
> 正在检查笔记更新... (路径: /Users/me/obsidian/Java)
[系统] 发现 3 个新文件，2 个修改文件。正在同步向量库...
[系统] 同步完成。当前知识库共 155 个文档。
> 请输入您的简历路径 (PDF): /Users/me/resume.pdf
[系统] 简历解析完成。
> 请输入面试主题: HashMap
[AI] (结合简历) 我看到你的简历中提到了在高并发场景下使用缓存的经验。请问在 Java 8 中 HashMap 是如何处理哈希冲突的？它在多线程环境下有什么问题？
> [用户输入回答...]
[AI] 评分: 75
[系统] 当前平均分: 75.0 [=======---]
[系统] “不错哦，离高薪更近一步了！🚀”
[AI] 好的，针对多线程问题，ConcurrentHashMap 是如何保证线程安全的？
...
> [用户输入 /finish]
[系统] 正在分析...
[系统] 报告已生成: ./reports/report_1.md
```
