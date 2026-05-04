package io.github.imzmq.interview.tool.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MCP Stdio 网关实现。
 * 
 * 职责：
 * 1. 本地进程通信：通过 Standard I/O (stdin/stdout) 与本地运行的 MCP Server 进程（如 Node.js 或 Python 脚本）交互。
 * 2. 状态机管理：负责进程的启动、指令写入、输出解析及超时销毁。
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp.stdio", name = "enabled", havingValue = "true")
public class McpStdioCapabilityGateway implements McpCapabilityGateway {
    private final ObjectMapper objectMapper;
    private final String command;
    private final String argsRaw;
    private final long timeoutMillis;
    private final ProcessStarter processStarter;

    public McpStdioCapabilityGateway(
            ObjectMapper objectMapper,
            @Value("${app.mcp.stdio.command:}") String command,
            @Value("${app.mcp.stdio.args:}") String argsRaw,
            @Value("${app.mcp.stdio.timeout-millis:3000}") long timeoutMillis
    ) {
        this(objectMapper, command, argsRaw, timeoutMillis, commandParts -> new ProcessBuilder(commandParts).start());
    }

    McpStdioCapabilityGateway(
            ObjectMapper objectMapper,
            String command,
            String argsRaw,
            long timeoutMillis,
            ProcessStarter processStarter
    ) {
        this.objectMapper = objectMapper;
        this.command = command == null ? "" : command.trim();
        this.argsRaw = argsRaw == null ? "" : argsRaw.trim();
        this.timeoutMillis = Math.max(300, timeoutMillis);
        this.processStarter = processStarter;
    }

    @Override
    public List<String> listCapabilities() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdOf(Map.of(), "capabilities"));
        request.put("method", "tools/list");
        request.put("params", Map.of());
        Object body = execute(request);
        return parseCapabilitiesBody(body);
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params) {
        return invokeCapability(name, params, Map.of());
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params, Map<String, Object> context) {
        String normalizedName = name == null ? "" : name.trim();
        Map<String, Object> normalizedParams = params == null ? Map.of() : params;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdOf(context, "invoke"));
        request.put("method", "tools/call");
        request.put("params", Map.of("name", normalizedName, "arguments", normalizedParams));
        Object body = execute(request);
        return parseInvokeBody(body);
    }

    private Object execute(Map<String, Object> request) {
        if (command.isBlank()) {
            throw new BusinessException(ErrorCode.MCP_STDIO_NOT_CONFIGURED, "mcp stdio command is not configured");
        }
        Process process;
        try {
            process = processStarter.start(buildCommandParts());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.MCP_UNREACHABLE, "mcp stdio process start failed", ex);
        }
        try {
            writeRequest(process, request);
            String output = waitAndReadOutput(process);
            if (output.isBlank()) {
                throw new BusinessException(ErrorCode.MCP_INVALID_RESPONSE, "mcp stdio empty response");
            }
            return objectMapper.readValue(output, Object.class);
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.MCP_INVALID_RESPONSE, "mcp stdio invalid response", ex);
        } finally {
            process.destroyForcibly();
        }
    }

    private void writeRequest(Process process, Map<String, Object> request) throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(request);
        try (OutputStream outputStream = process.getOutputStream()) {
            outputStream.write(payload);
            outputStream.write('\n');
            outputStream.flush();
        }
    }

    private String waitAndReadOutput(Process process) throws IOException {
        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MCP_TIMEOUT, "mcp stdio interrupted", ex);
        }
        if (!finished) {
            throw new BusinessException(ErrorCode.MCP_TIMEOUT, "mcp stdio timeout");
        }
        String merged = readStream(process.getInputStream(), process.getErrorStream()).trim();
        return extractJsonPayload(merged);
    }

    private String readStream(java.io.InputStream stdout, java.io.InputStream stderr) throws IOException {
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        merged.write(stdout.readAllBytes());
        merged.write('\n');
        merged.write(stderr.readAllBytes());
        return merged.toString(StandardCharsets.UTF_8);
    }

    private String extractJsonPayload(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "";
        }
        String[] lines = rawOutput.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String candidate = lines[i] == null ? "" : lines[i].trim();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                return candidate;
            }
        }
        return rawOutput.trim();
    }

    private List<String> buildCommandParts() {
        List<String> parts = new ArrayList<>();
        parts.add(command);
        if (!argsRaw.isBlank()) {
            String[] tokens = argsRaw.split("\\s+");
            for (String token : tokens) {
                if (token != null && !token.isBlank()) {
                    parts.add(token.trim());
                }
            }
        }
        return parts;
    }

    private Object parseInvokeBody(Object body) {
        if (body instanceof Map<?, ?> map) {
            if (map.containsKey("error")) {
                throw toRemoteProtocolException(map.get("error"));
            }
            if (map.containsKey("result")) {
                return parseInvokeResult(map.get("result"));
            }
        }
        if (body != null) {
            return body;
        }
        throw new BusinessException(ErrorCode.MCP_INVALID_RESPONSE, "mcp stdio invoke invalid response");
    }

    private List<String> parseCapabilitiesBody(Object body) {
        if (body instanceof Map<?, ?> map) {
            if (map.containsKey("error")) {
                throw toRemoteProtocolException(map.get("error"));
            }
            if (map.containsKey("result")) {
                return parseCapabilitiesResult(map.get("result"));
            }
            if (map.containsKey("capabilities")) {
                return parseCapabilitiesResult(map);
            }
        }
        if (body instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        throw new BusinessException(ErrorCode.MCP_INVALID_RESPONSE, "mcp stdio capabilities invalid response");
    }

    private List<String> parseCapabilitiesResult(Object resultBody) {
        if (resultBody instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        if (resultBody instanceof Map<?, ?> map) {
            Object tools = map.get("tools");
            if (tools instanceof List<?> list) {
                return list.stream()
                        .map(this::toolNameOf)
                        .filter(name -> name != null && !name.isBlank())
                        .toList();
            }
            Object capabilities = map.get("capabilities");
            if (capabilities instanceof List<?> list) {
                return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
            }
        }
        throw new BusinessException(ErrorCode.MCP_INVALID_RESPONSE, "mcp stdio capabilities invalid response");
    }

    private Object parseInvokeResult(Object resultBody) {
        if (resultBody == null) {
            throw new BusinessException(ErrorCode.MCP_INVALID_RESPONSE, "mcp stdio invoke invalid response");
        }
        if (resultBody instanceof Map<?, ?> map) {
            if (map.containsKey("structuredContent")) {
                return map.get("structuredContent");
            }
            if (map.containsKey("result")) {
                Object nested = map.get("result");
                if (nested == null) {
                    throw new BusinessException(ErrorCode.MCP_INVALID_RESPONSE, "mcp stdio invoke invalid response");
                }
                return nested;
            }
        }
        return resultBody;
    }

    private String requestIdOf(Map<String, Object> context, String prefix) {
        String traceId = traceIdOf(context);
        if (!traceId.isBlank()) {
            return prefix + "-" + traceId;
        }
        return prefix + "-" + System.nanoTime();
    }

    private String traceIdOf(Map<String, Object> context) {
        if (context == null) {
            return "";
        }
        Object rawTraceId = context.get("traceId");
        if (rawTraceId == null) {
            return "";
        }
        return String.valueOf(rawTraceId).trim();
    }

    private String toolNameOf(Object rawTool) {
        if (rawTool instanceof Map<?, ?> toolMap) {
            Object rawName = toolMap.get("name");
            return rawName == null ? "" : String.valueOf(rawName).trim();
        }
        if (rawTool == null) {
            return "";
        }
        return String.valueOf(rawTool).trim();
    }

    private BusinessException toRemoteProtocolException(Object errorBody) {
        String code = "";
        String message = "mcp remote error";
        if (errorBody instanceof Map<?, ?> map) {
            Object rawCode = map.get("code");
            if (rawCode != null) {
                code = String.valueOf(rawCode).trim();
            }
            Object rawMessage = map.get("message");
            if (rawMessage != null && !String.valueOf(rawMessage).isBlank()) {
                message = String.valueOf(rawMessage).trim();
            }
        }
        if ("-32602".equals(code) || "INVALID_PARAMS".equalsIgnoreCase(code)) {
            return new BusinessException(ErrorCode.MCP_INVALID_PARAMS, message);
        }
        if ("-32001".equals(code) || "PERMISSION_DENIED".equalsIgnoreCase(code) || "UNAUTHORIZED".equalsIgnoreCase(code)) {
            return new BusinessException(ErrorCode.FORBIDDEN, message);
        }
        if ("-32601".equals(code) || "METHOD_NOT_FOUND".equalsIgnoreCase(code) || "PROTOCOL_INCOMPATIBLE".equalsIgnoreCase(code)) {
            return new BusinessException(ErrorCode.MCP_PROTOCOL_INCOMPATIBLE, message);
        }
        return new BusinessException(ErrorCode.MCP_REMOTE_ERROR, message);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> commandParts) throws IOException;
    }
}

