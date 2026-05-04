package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.entity.chat.ChatMessageDO;
import io.github.imzmq.interview.entity.chat.ChatSessionDO;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import io.github.imzmq.interview.interview.application.ChatStreamingService;
import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import io.github.imzmq.interview.interview.application.WebChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final WebChatService webChatService;
    private final ChatStreamingService chatStreamingService;
    private final UserIdentityResolver userIdentityResolver;

    public ChatController(WebChatService webChatService,
                          ChatStreamingService chatStreamingService,
                          UserIdentityResolver userIdentityResolver) {
        this.webChatService = webChatService;
        this.chatStreamingService = chatStreamingService;
        this.userIdentityResolver = userIdentityResolver;
    }

    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionDO> createSession(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        // 从请求上下文中解析当前用户身份（Web 端多会话隔离关键点）。
        String userId = userIdentityResolver.resolve(request);
        // 允许前端不传 title，此时默认“新对话”。
        String title = body != null ? body.getOrDefault("title", "新对话") : "新对话";
        return ResponseEntity.ok(webChatService.createSession(userId, title));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSessionDO>> listSessions(HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(webChatService.listSessions(userId));
    }

    @PutMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSessionDO> renameSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(webChatService.renameSession(sessionId, body.get("title")));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        webChatService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageDO>> listMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long beforeId) {
        return ResponseEntity.ok(webChatService.listMessages(sessionId, limit, beforeId));
    }

    @PostMapping(value = "/sessions/{sessionId}/stream",
            produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamChat(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        // 鉴权后拿到 userId，防止跨用户访问会话。
        String userId = userIdentityResolver.resolve(request);
        // 用户本次输入内容。
        String content = String.valueOf(body.getOrDefault("content", ""));
        // 可选检索模式（RAG_ONLY / 混合策略等），空值时由下游决定默认行为。
        String retrievalModeValue = body == null ? null : String.valueOf(body.getOrDefault("retrievalMode", ""));
        KnowledgeRetrievalMode retrievalMode = KnowledgeRetrievalMode.fromNullable(retrievalModeValue, null);
        // 转入应用层流式服务，返回 SSE emitter 给前端持续接收 token/event。
        return chatStreamingService.streamChat(sessionId, userId, content, retrievalMode);
    }

    @PostMapping("/stream/stop")
    public ResponseEntity<Map<String, Object>> stopStream(
            @RequestBody Map<String, String> body) {
        // 前端通过 streamTaskId 精确停止某一个流式任务。
        String taskId = body.get("streamTaskId");
        boolean stopped = chatStreamingService.stopTask(taskId);
        return ResponseEntity.ok(Map.of("streamTaskId", taskId, "stopped", stopped));
    }
}









