package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.agent.task.TaskType;
import io.github.imzmq.interview.core.trace.RAGTraceContext;
import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import io.github.imzmq.interview.interview.api.support.RequestPayloadMapper;
import io.github.imzmq.interview.interview.application.InterviewService;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.mcp.application.OpsAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 面试系统 HTTP 入口层 — 核心面试流程。
 * 负责参数解析、基础校验、错误码映射，并把业务请求转发到服务层。
 *
 * <p>接口类别：</p>
 * <ul>
 *   <li>面试主流程：/start /answer /report</li>
 *   <li>通用任务路由：/task/dispatch（对外统一入口，内部再路由到各 Agent）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class InterviewController {

    private final InterviewService interviewService;
    private final UserIdentityResolver userIdentityResolver;
    private final OpsAuditService opsAuditService;
    private final RequestPayloadMapper requestPayloadMapper;

    public InterviewController(
            InterviewService interviewService,
            UserIdentityResolver userIdentityResolver,
            OpsAuditService opsAuditService,
            RequestPayloadMapper requestPayloadMapper
    ) {
        this.interviewService = interviewService;
        this.userIdentityResolver = userIdentityResolver;
        this.opsAuditService = opsAuditService;
        this.requestPayloadMapper = requestPayloadMapper;
    }

    /**
     * 创建一轮新的模拟面试会话，并返回首题。
     *
     * @param payload 包含 topic (面试主题), resumePath (简历路径), totalQuestions (总题数)
     * @param request HTTP 请求，用于解析用户身份
     * @return 新建的面试会话对象
     */
    @PostMapping("/start")
    public InterviewSession startSession(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        String topic = payload.get("topic") == null ? "" : payload.get("topic").toString();
        String resumePath = payload.get("resumePath") == null ? "" : payload.get("resumePath").toString();
        String traceId = payload.get("traceId") == null ? UUID.randomUUID().toString() : payload.get("traceId").toString();

        RAGTraceContext.setTraceId(traceId);
        try {
            Integer totalQuestions = null;
            Object rawTotal = payload.get("totalQuestions");
            if (rawTotal instanceof Number) {
                totalQuestions = ((Number) rawTotal).intValue();
            } else if (rawTotal instanceof String) {
                String raw = (String) rawTotal;
                if (!raw.isBlank()) {
                    try {
                        totalQuestions = Integer.parseInt(raw.trim());
                    } catch (NumberFormatException ignored) {
                        totalQuestions = null;
                    }
                }
            }
            return interviewService.startSession(userId, topic, resumePath, totalQuestions);
        } finally {
            RAGTraceContext.clear();
        }
    }

    /**
     * 提交当前题目的作答文本，返回评分、反馈和下一题。
     *
     * @param payload 包含 sessionId 和 answer
     * @return 答题分析结果及下一题
     */
    @PostMapping("/answer")
    public InterviewService.AnswerResult submitAnswer(@RequestBody Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        String answer = payload.get("answer");
        String traceId = payload.getOrDefault("traceId", UUID.randomUUID().toString());

        RAGTraceContext.setTraceId(traceId);
        try {
            return interviewService.submitAnswer(sessionId, answer);
        } finally {
            RAGTraceContext.clear();
        }
    }

    /**
     * 结束后生成整场面试总结报告。
     *
     * @param payload 包含 sessionId
     * @param request HTTP 请求，用于解析用户身份
     * @return 最终面试报告
     */
    @PostMapping("/report")
    public InterviewService.FinalReport report(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        String sessionId = payload.get("sessionId");
        String userId = userIdentityResolver.resolve(request);
        String traceId = payload.getOrDefault("traceId", UUID.randomUUID().toString());

        RAGTraceContext.setTraceId(traceId);
        try {
            return interviewService.generateFinalReport(sessionId, userId);
        } finally {
            RAGTraceContext.clear();
        }
    }

    /**
     * 通用任务分发接口。
     * 将请求路由到不同的 Agent 处理（如学习路径规划、代码练习生成等）。
     *
     * @param payload 包含 taskType, payload (任务参数), context (上下文信息)
     * @param request HTTP 请求，用于解析操作者身份
     * @return 任务处理响应
     */
    @PostMapping("/task/dispatch")
    public ResponseEntity<?> dispatchTask(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        // operator 表示"本次调用的操作者"，用于审计；context.userId 表示"业务主体用户"，可与 operator 不同。
        String operator = userIdentityResolver.resolve(request);
        String taskTypeRaw = payload.get("taskType") == null ? "" : payload.get("taskType").toString();
        if (taskTypeRaw.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "taskType 不能为空"));
        }
        TaskType taskType;
        try {
            taskType = TaskType.valueOf(taskTypeRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "不支持的 taskType: " + taskTypeRaw));
        }
        Map<String, Object> taskPayload = requestPayloadMapper.toObjectMap(payload.get("payload"));
        Map<String, Object> context = requestPayloadMapper.toObjectMap(payload.get("context"));
        String traceId = requestPayloadMapper.resolveTraceId(context);
        RAGTraceContext.setTraceId(traceId);

        try {
            context = requestPayloadMapper.ensureUserId(context, operator);
            context = requestPayloadMapper.ensureTraceId(context, traceId);

            TaskResponse response = interviewService.dispatchTask(taskType, taskPayload, context);
            opsAuditService.record(
                    operator,
                    "TASK_DISPATCH",
                    Map.of("taskType", taskType.name(), "contextUserId", String.valueOf(context.get("userId"))),
                    response.success(),
                    response.message()
            );
            if (response.success()) {
                return ResponseEntity.ok(response);
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } finally {
            RAGTraceContext.clear();
        }
    }
}
