package com.example.interview.service;

import com.example.interview.core.InterviewSession;
import com.example.interview.core.RAGTraceContext;
import com.example.interview.stream.InterviewSseEmitterSender;
import com.example.interview.stream.InterviewStreamEventType;
import com.example.interview.stream.InterviewStreamTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class InterviewStreamingService {
    private static final Logger logger = LoggerFactory.getLogger(InterviewStreamingService.class);

    private final InterviewService interviewService;
    private final InterviewStreamTaskManager taskManager;
    private final Executor interviewStreamingExecutor;
    private final long timeoutMillis;
    private final int messageChunkSize;

    public InterviewStreamingService(
            InterviewService interviewService,
            InterviewStreamTaskManager taskManager,
            @Qualifier("interviewStreamingExecutor") Executor interviewStreamingExecutor,
            @Value("${app.interview.streaming.timeout-millis:180000}") long timeoutMillis,
            @Value("${app.interview.streaming.message-chunk-size:48}") int messageChunkSize
    ) {
        this.interviewService = interviewService;
        this.taskManager = taskManager;
        this.interviewStreamingExecutor = interviewStreamingExecutor;
        this.timeoutMillis = timeoutMillis;
        this.messageChunkSize = Math.max(1, messageChunkSize);
    }

    public SseEmitter streamStart(Map<String, Object> payload, String userId) {
        String traceId = resolveTraceId(payload);
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        String taskId = taskManager.newTaskId();
        InterviewSseEmitterSender sender = new InterviewSseEmitterSender(emitter);
        taskManager.register(taskId, sender);
        taskManager.bindLifecycle(taskId, emitter);
        sender.sendEvent(InterviewStreamEventType.META.value(), Map.of(
                "streamTaskId", taskId,
                "action", "start",
                "traceId", traceId
        ));
        interviewStreamingExecutor.execute(() -> runStart(payload, userId, traceId, taskId, sender));
        return emitter;
    }

    public SseEmitter streamAnswer(Map<String, Object> payload) {
        String traceId = resolveTraceId(payload);
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        String taskId = taskManager.newTaskId();
        InterviewSseEmitterSender sender = new InterviewSseEmitterSender(emitter);
        taskManager.register(taskId, sender);
        taskManager.bindLifecycle(taskId, emitter);
        sender.sendEvent(InterviewStreamEventType.META.value(), Map.of(
                "streamTaskId", taskId,
                "action", "answer",
                "traceId", traceId
        ));
        interviewStreamingExecutor.execute(() -> runAnswer(payload, traceId, taskId, sender));
        return emitter;
    }

    public SseEmitter streamReport(Map<String, Object> payload, String userId) {
        String traceId = resolveTraceId(payload);
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        String taskId = taskManager.newTaskId();
        InterviewSseEmitterSender sender = new InterviewSseEmitterSender(emitter);
        taskManager.register(taskId, sender);
        taskManager.bindLifecycle(taskId, emitter);
        sender.sendEvent(InterviewStreamEventType.META.value(), Map.of(
                "streamTaskId", taskId,
                "action", "report",
                "traceId", traceId
        ));
        interviewStreamingExecutor.execute(() -> runReport(payload, userId, traceId, taskId, sender));
        return emitter;
    }

    public boolean stopTask(String streamTaskId) {
        return taskManager.cancel(streamTaskId, "已停止生成");
    }

    private void runStart(Map<String, Object> payload, String userId, String traceId, String taskId, InterviewSseEmitterSender sender) {
        // [BUG FIX] 必须首先将父线程传递过来的 traceId 设置到当前执行线程的 ThreadLocal 中，保证后续 RAGService 能拿到同一个 traceId。
        RAGTraceContext.setTraceId(traceId);
        try {
            if (isStopped(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), progress("PREPARING", "正在准备会话", 15));
            String topic = asString(payload, "topic", "高级后端开发工程师");
            String resumePath = asString(payload, "resumePath", "");
            Integer totalQuestions = asInt(payload, "totalQuestions");
            InterviewSession session = interviewService.startSession(userId, topic, resumePath, totalQuestions);
            if (isStopped(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), progress("GENERATING_QUESTION", "正在生成首题", 70));
            sendChunked(sender, "question", session.getCurrentQuestion());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", session.getId());
            result.put("topic", session.getTopic());
            result.put("currentQuestion", session.getCurrentQuestion());
            result.put("currentQuestionIndex", 1);
            result.put("totalQuestions", session.getTotalQuestions());
            result.put("averageScore", session.getAverageScore());
            finish(taskId, sender, "start", result);
        } catch (Exception ex) {
            fail(taskId, sender, "INTERVIEW_STREAM_START_FAILED", ex);
        } finally {
            RAGTraceContext.clear();
        }
    }

    private void runAnswer(Map<String, Object> payload, String traceId, String taskId, InterviewSseEmitterSender sender) {
        // [BUG FIX] 确保异步线程拥有 trace 上下文
        RAGTraceContext.setTraceId(traceId);
        try {
            if (isStopped(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), progress("EVALUATING", "正在评估回答", 20));
            String sessionId = asString(payload, "sessionId", "");
            String answer = asString(payload, "answer", "");
            InterviewService.AnswerResult result = interviewService.submitAnswer(sessionId, answer);
            if (isStopped(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), progress("GENERATING_FEEDBACK", "正在生成反馈", 65));
            sendChunked(sender, "feedback", result.feedback());
            if (!result.finished()) {
                sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), progress("PREPARING_NEXT_QUESTION", "正在准备下一题", 82));
                sendChunked(sender, "question", result.nextQuestion());
            }
            Map<String, Object> finishPayload = new LinkedHashMap<>();
            finishPayload.put("score", result.score());
            finishPayload.put("averageScore", result.averageScore());
            finishPayload.put("finished", result.finished());
            finishPayload.put("nextQuestion", result.nextQuestion());
            finishPayload.put("answeredCount", result.answeredCount());
            finishPayload.put("totalQuestions", result.totalQuestions());
            finishPayload.put("difficultyLevel", result.difficultyLevel());
            finishPayload.put("followUpState", result.followUpState());
            finish(taskId, sender, "answer", finishPayload);
        } catch (Exception ex) {
            fail(taskId, sender, "INTERVIEW_STREAM_ANSWER_FAILED", ex);
        } finally {
            RAGTraceContext.clear();
        }
    }

    private void runReport(Map<String, Object> payload, String userId, String traceId, String taskId, InterviewSseEmitterSender sender) {
        // [BUG FIX] 确保异步线程拥有 trace 上下文
        RAGTraceContext.setTraceId(traceId);
        try {
            if (isStopped(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), progress("ANALYZING", "正在分析面试表现", 30));
            String sessionId = asString(payload, "sessionId", "");
            InterviewService.FinalReport report = interviewService.generateFinalReport(sessionId, userId);
            if (isStopped(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), progress("GENERATING_REPORT", "正在生成复盘报告", 75));
            sendChunked(sender, "report", buildReportText(report));
            Map<String, Object> finishPayload = new LinkedHashMap<>();
            finishPayload.put("summary", report.summary());
            finishPayload.put("incomplete", report.incomplete());
            finishPayload.put("weak", report.weak());
            finishPayload.put("wrong", report.wrong());
            finishPayload.put("obsidianUpdates", report.obsidianUpdates());
            finishPayload.put("nextFocus", report.nextFocus());
            finishPayload.put("averageScore", report.averageScore());
            finishPayload.put("answeredCount", report.answeredCount());
            finish(taskId, sender, "report", finishPayload);
        } catch (Exception ex) {
            fail(taskId, sender, "INTERVIEW_STREAM_REPORT_FAILED", ex);
        } finally {
            RAGTraceContext.clear();
        }
    }

    private boolean isStopped(String taskId) {
        return taskManager.isCancelled(taskId);
    }

    private void finish(String taskId, InterviewSseEmitterSender sender, String action, Map<String, Object> result) {
        if (isStopped(taskId)) {
            return;
        }
        sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                "action", action,
                "result", result
        ));
        sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    private void fail(String taskId, InterviewSseEmitterSender sender, String code, Exception ex) {
        logger.warn("interview stream failed, taskId={}, code={}", taskId, code, ex);
        if (!isStopped(taskId)) {
            sender.sendEvent(InterviewStreamEventType.ERROR.value(), Map.of(
                    "code", code,
                    "message", ex.getMessage() == null ? "流式处理失败，请稍后重试" : ex.getMessage()
            ));
            sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
        }
        taskManager.unregister(taskId);
        sender.complete();
    }

    private Map<String, Object> progress(String stage, String label, int percent) {
        return Map.of(
                "stage", stage,
                "label", label,
                "status", "running",
                "percent", percent
        );
    }

    private void sendChunked(InterviewSseEmitterSender sender, String channel, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        int length = content.length();
        int index = 0;
        while (index < length) {
            int end = Math.min(length, index + messageChunkSize);
            sender.sendEvent(InterviewStreamEventType.MESSAGE.value(), Map.of(
                    "channel", channel,
                    "delta", content.substring(index, end)
            ));
            index = end;
        }
    }

    private String buildReportText(InterviewService.FinalReport report) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "综合评价", report.summary());
        appendSection(builder, "不完整点", report.incomplete());
        appendSection(builder, "薄弱点", report.weak());
        appendSection(builder, "误区", report.wrong());
        appendSection(builder, "下一步建议", report.nextFocus());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(title).append("：\n").append(content.trim());
    }

    private String resolveTraceId(Map<String, Object> payload) {
        if (payload == null) {
            return UUID.randomUUID().toString();
        }
        Object trace = payload.get("traceId");
        String traceText = trace == null ? "" : String.valueOf(trace).trim();
        return traceText.isBlank() ? UUID.randomUUID().toString() : traceText;
    }

    private String asString(Map<String, Object> payload, String key, String defaultValue) {
        if (payload == null) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private Integer asInt(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
