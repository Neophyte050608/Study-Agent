package com.example.interview.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnPayloadTooLargeForOversizedUpload() {
        ResponseEntity<Map<String, String>> response = handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(21 * 1024 * 1024));
        assertEquals(413, response.getStatusCode().value());
        assertEquals("上传文件超过服务端限制，请上传 20MB 以内 PDF", response.getBody().get("message"));
    }

    @Test
    void shouldReturnBadRequestForIllegalArgument() {
        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(new IllegalArgumentException("sessionId 不合法"));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("sessionId 不合法", response.getBody().get("message"));
    }

    @Test
    void shouldReturnBadRequestForIllegalArgumentWithNullMessage() {
        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(new IllegalArgumentException());
        assertEquals(400, response.getStatusCode().value());
        assertEquals("请求参数不合法", response.getBody().get("message"));
    }

    @Test
    void shouldReturnInternalServerErrorForGenericException() {
        ResponseEntity<Map<String, String>> response = handler.handleGenericException(new RuntimeException("unexpected"));
        assertEquals(500, response.getStatusCode().value());
        // 不应暴露内部错误详情
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("message").contains("服务器内部错误"));
    }
}
