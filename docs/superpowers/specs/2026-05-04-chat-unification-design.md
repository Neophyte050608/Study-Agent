# Chat 统一：合并面试与刷题子页面到主聊天

## 背景

项目有三个页面对应同一组后端能力：ChatView（主聊天）、InterviewView（面试子页面）、CodingView（刷题子页面）。后端意图路由在主聊天中已能正确分发面试和刷题请求，两个子页面是冗余 UI 壳。InterviewView（454 行）和 CodingView（255 行）长期未维护，功能健全度远低于 ChatView（1163 行）。

目标：删除冗余子页面，将独有价值（计时器、分数环、语音输入、快捷入口）嵌入主聊天，统一到同一套会话体系。

## 整体架构

### 删除
- `frontend/src/views/InterviewView.vue`（454 行）
- `frontend/src/views/CodingView.vue`（255 行）
- router 中 `/interview` 和 `/coding` 路由定义

### 新增
- `frontend/src/views/chat/InterviewCard.vue` — 嵌入式面试卡片组件
- ChatView 空状态区 QuickAction 快捷入口 pills

### 修改
- `router/index.js` — 移除路由，旧 URL 重定向到 `/chat`
- `AppShell.vue` — sidebar 面试/刷题菜单项指向 `/chat`
- `ChatView.vue` — 消息流中渲染 InterviewCard，空状态加 pills，新增 4 个 interview handler

### 后端
- 不变。`InterviewStreamController`、`CodingPracticeController`、`ChatController` 全部保留。

## 数据流

```
用户输入 "开始面试" → ChatView SSE 流 → 意图路由 → 面试 Agent
→ SSE 下发结构化消息(type=interview_card) → ChatView 渲染 InterviewCard
→ 用户答题(计时/语音) → InterviewCard emit submit → ChatView 调用 submitInterviewAnswerStream
→ SSE 流式反馈 → InterviewCard 更新评分环与反馈文本
→ 用户点"下一题" → next question stream → InterviewCard 重置计时器与新题目
→ 最后一题完成 → generateInterviewReportStream → 报告呈现
```

## InterviewCard 组件

### Props
- `payload` — `{ state, question, questionIndex, totalQuestions, score, feedback, streaming }`
- `payload.interviewState` — `'asking' | 'submitting' | 'feedback' | 'finished'`
- `messageId` — 关联的聊天消息 ID
- `chatSessionId` — 当前聊天会话 ID

### States 与 UI

**asking（答题中）：**
- 题目文本 + 进度 "第 03/10 题"
- 计时器（自增，组件内部维护）
- 输入框（支持 Shift+Enter 换行，Enter 提交）
- 语音输入按钮（复用 InterviewView 的 SpeechRecognition 逻辑）
- 提交回答 / 停止按钮

**submitting（提交中）：**
- 题目保持显示
- 输入框禁用，显示加载动画
- 停止按钮可用

**feedback（反馈）：**
- 分数环（SVG 环形进度条，展示分数/满分）
- 反馈文本（Markdown 渲染）
- "下一题" 按钮 / "生成报告" 按钮（最后一题时）

**finished（完成）：**
- 最终报告 Markdown 渲染
- 分数汇总

### Events（emit 给 ChatView 处理）
- `@submit({ messageId, answer })` — 提交答案
- `@stop({ taskId })` — 停止生成
- `@next({ messageId, sessionId })` — 下一题
- `@finish({ messageId })` — 生成最终报告

### 语音输入
复用 InterviewView 的 Web Speech API 逻辑：
- `SpeechRecognition` 中文识别，持续模式
- 最终结果追加到输入框
- 浏览器不支持时隐藏按钮

### 计时器
- 组件内部 `setInterval` 自增
- `asking` 状态启动，`feedback`/`finished` 停表
- `mm:ss` 格式展示

## ChatView 改动

### 消息流渲染
在现有 QuizCard/ScenarioCard/FillCard 渲染旁增加：
```html
<InterviewCard
  v-if="msg.interviewPayload"
  :payload="msg.interviewPayload"
  :message-id="msg.messageId"
  :chat-session-id="currentSessionId"
  @submit="handleInterviewSubmit"
  @stop="handleInterviewStop"
  @next="handleInterviewNext"
  @finish="handleInterviewFinish"
/>
```

### 空状态 QuickAction Pills
在 "有什么我能帮你的吗？" 下方加 4 个快捷入口：
- "3 道 Spring Boot 场景题"
- "Redis 选择题"
- "开始面试"
- "直接开始刷题"

点击后填充输入框内容，用户可直接发送。

### 新增 Handler 函数
- `handleInterviewSubmit({ messageId, answer })` — 调用 `submitInterviewAnswerStream()`
- `handleInterviewStop({ taskId })` — 调用 `stopInterviewStream()`
- `handleInterviewNext({ messageId, sessionId })` — 请求下一题
- `handleInterviewFinish({ messageId })` — 调用 `generateInterviewReportStream()`

流式结果通过更新对应消息的 `interviewPayload` 驱动 InterviewCard 响应式渲染。

### 消息规范化
在 `normalizeMessages()` 中增加对 `contentType === 'interview_card'` 的解析，将 JSON content 映射为 `interviewPayload`。

## 路由清理

- 删除 `/interview`（含别名 `interview.html`）、`/coding`（含别名 `coding.html`、`practice`、`practice.html`）路由定义
- 保留兼容：旧 URL 访问时重定向到 `/chat`
- AppShell `toSpaPath()` 中 `/interview`、`/coding`、`/practice` 系列返回 `/chat`
- 不影响 sidebar 菜单加载（菜单项由后端控制）

## 后端

不动。三个 Controller 全部保留。`/api/task/dispatch` 端点后续单独评估清理。

## 净效果

- 删除 ~709 行未维护代码
- 新增 ~200 行（InterviewCard 组件 + ChatView 改动）
- Net 减少 ~500 行
- 面试/刷题/闲聊共享同一套会话历史、粘贴、点赞、stream 控制、检索模式切换等能力
- 保留计时器、分数环、语音输入等用户喜欢的交互

## 风险

- 低风险。后端 API 不变，前端仅做 UI 层合并
- InterviewCard 作为独立组件开发，不影响现有 ChatView 聊天功能
- 旧 URL 做重定向兼容，不影响已有的书签/分享链接
