package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import io.github.imzmq.interview.interview.api.support.RequestPayloadMapper;
import io.github.imzmq.interview.interview.application.InterviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class McpController {

    private final InterviewService interviewService;
    private final UserIdentityResolver userIdentityResolver;
    private final RequestPayloadMapper requestPayloadMapper;

    public McpController(
            InterviewService interviewService,
            UserIdentityResolver userIdentityResolver,
            RequestPayloadMapper requestPayloadMapper
    ) {
        this.interviewService = interviewService;
        this.userIdentityResolver = userIdentityResolver;
        this.requestPayloadMapper = requestPayloadMapper;
    }

    /**
     * 获取 MCP (Model Context Protocol) 工具能力列表。
     *
     * @param traceId 可选的调用链路 ID
     * @param request HTTP 请求
     * @return 包含用户 ID、链路 ID 和能力列表的响应
     */
    @GetMapping("/mcp/capabilities")
    public ResponseEntity<?> mcpCapabilities(
            @RequestParam(value = "traceId", required = false) String traceId,
            HttpServletRequest request
    ) {
        // 能力发现支持显式 traceId，便于将调用与后续 invoke 关联。
        String userId = userIdentityResolver.resolve(request);
        String resolvedTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId.trim();
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "traceId", resolvedTraceId,
                "capabilities", interviewService.discoverMcpCapabilities(userId, resolvedTraceId)
        ));
    }

    /**
     * 调用指定的 MCP 工具能力。
     *
     * @param payload 包含 capability (能力名), params (入参), context (上下文)
     * @param request HTTP 请求
     * @return 工具调用结果
     */
    @PostMapping("/mcp/invoke")
    public ResponseEntity<?> invokeMcp(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        // MCP 调用入口：capability 对应某个工具能力名；params 为工具入参；context 用于链路与身份透传。
        String userId = userIdentityResolver.resolve(request);
        String capability = payload.get("capability") == null ? "" : payload.get("capability").toString().trim();
        if (capability.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "capability 不能为空"));
        }
        Map<String, Object> params = requestPayloadMapper.toObjectMap(payload.get("params"));
        Map<String, Object> context = requestPayloadMapper.ensureTraceId(
                requestPayloadMapper.toObjectMap(payload.get("context")),
                null
        );
        Map<String, Object> result = interviewService.invokeMcpCapability(userId, capability, params, context);
        return ResponseEntity.ok(result);
    }
}
