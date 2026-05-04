package io.github.imzmq.interview.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 自动将 Controller 成功返回值包装为 {@link ApiResponse}。
 *
 * <p>跳过包装: ApiResponse 自身、ResponseEntity、SseEmitter、StreamingResponseBody、Resource。</p>
 */
@RestControllerAdvice
public class ApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> type = returnType.getParameterType();
        if (ApiResponse.class.isAssignableFrom(type)) return false;
        if (ResponseEntity.class.isAssignableFrom(type)) return false;
        if (SseEmitter.class.isAssignableFrom(type)) return false;
        if (StreamingResponseBody.class.isAssignableFrom(type)) return false;
        if (Resource.class.isAssignableFrom(type)) return false;
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String path = "";
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest req = servletRequest.getServletRequest();
            path = req.getRequestURI();
        }
        return ApiResponse.ok(body, path);
    }
}
