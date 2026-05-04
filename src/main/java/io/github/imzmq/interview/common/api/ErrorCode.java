package io.github.imzmq.interview.common.api;

/**
 * 统一错误码枚举。
 *
 * <p>编码规则: A_BB_CC 五位数字，千位决定 HTTP 大类，百十位为模块编号，个位为具体场景。</p>
 */
public enum ErrorCode {

    // ==================== 通用 (10000) ====================
    BAD_REQUEST(10001, "请求参数不合法", false),
    UNAUTHORIZED(20002, "未认证", false),
    FORBIDDEN(20003, "无权限", false),
    NOT_FOUND(30004, "资源不存在", false),
    METHOD_NOT_ALLOWED(10005, "请求方法不支持", false),
    INTERNAL_ERROR(90000, "服务器内部错误", false),

    // ==================== Interview (10100) ====================
    INTERVIEW_SESSION_NOT_FOUND(30101, "面试会话不存在", false),
    INTERVIEW_ALREADY_ENDED(40102, "面试已结束", false),
    INTERVIEW_ANSWER_SUBMIT_FAILED(50103, "答案提交失败", false),
    INTERVIEW_REPORT_GEN_FAILED(50104, "面试报告生成失败", false),
    INTERVIEW_INGEST_FAILED(50105, "知识摄入失败", false),
    INTERVIEW_STREAM_FAILED(50106, "面试流式响应失败", false),

    // ==================== Knowledge / RAG (10200) ====================
    KNOWLEDGE_BASE_NOT_FOUND(30201, "知识库不存在", false),
    RAG_RETRIEVAL_FAILED(50202, "知识检索失败", true),
    RAG_INDEX_FAILED(50203, "知识索引失败", true),
    RAG_EVAL_FAILED(50204, "检索评测失败", false),
    RAG_EVAL_DISABLED(40205, "检索评测已关闭，请设置 app.observability.retrieval-eval-enabled=true 后重试", false),
    RAG_TRACE_QUERY_FAILED(50206, "RAG 链路追踪查询失败", false),

    // ==================== LocalGraph (10250) ====================
    LOCAL_GRAPH_INDEX_NOT_CONFIGURED(40251, "本地知识图索引未配置", false),
    LOCAL_GRAPH_INDEX_NOT_FOUND(30252, "本地知识图索引未找到", false),
    LOCAL_GRAPH_INDEX_LOAD_FAILED(50253, "本地知识图索引加载失败", true),
    LOCAL_GRAPH_INDEX_SCHEMA_MISMATCH(50254, "本地知识图索引 schema 不匹配", false),
    LOCAL_GRAPH_INDEX_EMPTY(40255, "本地知识图索引为空", false),
    LOCAL_GRAPH_CANDIDATE_RECALL_EMPTY(40256, "本地知识图候选召回为空", false),
    LOCAL_GRAPH_OLLAMA_UNAVAILABLE(50257, "Ollama 服务不可用", true),
    LOCAL_GRAPH_OLLAMA_TIMEOUT(50258, "Ollama 调用超时", true),
    LOCAL_GRAPH_OLLAMA_INVALID_JSON(50259, "Ollama 返回 JSON 非法", false),
    LOCAL_GRAPH_ROUTING_EMPTY(40260, "本地路由结果为空", false),
    LOCAL_GRAPH_NOTE_FILE_NOT_FOUND(30261, "笔记文件未找到", false),
    LOCAL_GRAPH_NOTE_FILE_OUTSIDE_VAULT(10262, "笔记文件路径越界", false),
    LOCAL_GRAPH_NOTE_PARSE_FAILED(50263, "笔记文件解析失败", false),
    LOCAL_GRAPH_CONTEXT_TOO_THIN(40264, "本地图检索上下文不足", false),
    LOCAL_GRAPH_NOT_IMPLEMENTED(50265, "本地图检索功能未实现", false),

    // ==================== Routing (10300) ====================
    INTENT_CLASSIFY_FAILED(50301, "意图分类失败", true),
    INTENT_TREE_NOT_READY(50302, "意图树未就绪", false),
    CLARIFICATION_FAILED(50303, "澄清解析失败", false),
    SLOT_EXTRACTION_FAILED(50304, "槽位提取失败", false),

    // ==================== ModelRouting (10400) ====================
    MODEL_NO_CANDIDATE(50401, "无可用模型候选", true),
    MODEL_ALL_FAILED(50402, "全部候选模型调用失败", true),
    MODEL_STREAM_EMPTY(50403, "模型流式返回为空", false),
    MODEL_RESPONSE_EMPTY(50404, "模型返回为空", false),
    MODEL_PROBE_EMPTY(50405, "首包为空", false),
    MODEL_PROBE_SHORT(50406, "首包长度不足", false),
    MODEL_PROBE_FAILED(50407, "首包探测失败", true),
    MODEL_PROBE_TIMEOUT(50408, "首Token探测超时", true),
    MODEL_RESPONSE_TIMEOUT(50409, "总响应超时", true),
    MODEL_CALL_FAILED(50410, "模型调用失败", true),
    MODEL_INSTANCE_NOT_FOUND(30411, "找不到可用模型实例", false),

    // ==================== MCP / Tool (10500) ====================
    MCP_TIMEOUT(50501, "MCP 调用超时", true),
    MCP_INVALID_RESPONSE(50502, "MCP 响应格式非法", false),
    MCP_REMOTE_ERROR(50503, "MCP 远端调用失败", true),
    MCP_UNREACHABLE(50504, "MCP 无法连接", true),
    MCP_STDIO_NOT_CONFIGURED(10505, "MCP stdio 未配置", false),
    MCP_SSE_NOT_CONFIGURED(10506, "MCP SSE 未配置", false),
    MCP_BRIDGE_NOT_CONFIGURED(10507, "MCP bridge 未配置", false),
    MCP_FASTMCP_NOT_CONFIGURED(10508, "MCP FastMCP 未配置", false),
    MCP_INVALID_PARAMS(10509, "MCP 参数非法", false),
    MCP_PROTOCOL_INCOMPATIBLE(50510, "MCP 协议不兼容", false),

    // ==================== Ingestion (10600) ====================
    INGESTION_PIPELINE_DISABLED(50601, "入库管道已禁用", false),
    INGESTION_PIPELINE_MISSING_STAGE(50602, "入库管道缺少阶段", false),
    INGESTION_PARSE_FAILED(50603, "文件解析失败", false),
    INGESTION_CHUNK_FAILED(50604, "文档分块失败", false),
    INGESTION_FILE_READ_FAILED(50605, "读取上传文件失败", false),
    INGESTION_STAGE_NOT_FOUND(30606, "入库阶段节点未找到", false),

    // ==================== Media (10700) ====================
    IMAGE_UPLOAD_FAILED(50701, "图片上传失败", false),
    IMAGE_INDEX_FAILED(50702, "图片索引失败", true),
    IMAGE_SEARCH_FAILED(50703, "图片搜索失败", false),

    // ==================== Chat / Prompt (10800) ====================
    PROMPT_TEMPLATE_NOT_FOUND(30801, "Prompt 模板未找到", false),
    CONTEXT_COMPRESS_FAILED(50802, "上下文压缩失败", false),
    CONVERSATION_TRACK_FAILED(50803, "对话追踪失败", false),

    // ==================== Agent (10900) ====================
    AGENT_CONFIG_NOT_FOUND(30901, "Agent 配置未找到", false),
    AGENT_EXECUTION_FAILED(50902, "Agent 执行失败", true),
    TASK_ROUTER_FAILED(50903, "任务路由失败", false),

    // ==================== Skill (11000) ====================
    SKILL_EXECUTION_FAILED(51001, "技能执行失败", true),
    SKILL_INPUT_MISSING(11002, "技能输入缺失", false),

    // ==================== IM (11100) ====================
    IM_WEBHOOK_FAILED(51101, "IM Webhook 调用失败", true),

    // ==================== Identity (11200) ====================
    IDENTITY_EXTRACT_FAILED(51201, "用户身份提取失败", false),

    // ==================== Search (11300) ====================
    SEARCH_FAILED(51301, "搜索失败", false),

    // ==================== Learning (11400) ====================
    LEARNING_PROFILE_FAILED(51401, "学习画像处理失败", false),

    // ==================== Observability (11500) ====================
    OBSERVABILITY_TRACE_CLEAN_FAILED(51501, "链路清洗失败", false),

    // ==================== Security (10050) ====================
    ENCRYPTION_KEY_MISSING(50051, "缺少加密密钥，必须提供 32 字节 AES 密钥", false),
    ENCRYPTION_KEY_DEFAULT_FORBIDDEN(50052, "禁止使用默认加密密钥", false),
    ENCRYPTION_KEY_LENGTH_INVALID(50053, "加密密钥长度非法", false),
    ENCRYPTION_FAILED(50054, "加密失败", false),
    DECRYPTION_FAILED(50055, "解密失败", false),
    CIPHER_FORMAT_INVALID(10056, "密文格式非法", false);

    private final int code;
    private final String defaultMessage;
    private final boolean retryable;

    ErrorCode(int code, String defaultMessage, boolean retryable) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public int code() { return code; }
    public String defaultMessage() { return defaultMessage; }
    public boolean retryable() { return retryable; }
}
