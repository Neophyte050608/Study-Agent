package com.example.interview.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void shouldReturnPayloadTooLargeForOversizedUpload() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<Map<String, String>> response = handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(12 * 1024 * 1024));
        assertEquals(413, response.getStatusCode().value());
        assertEquals("上传文件超过服务端限制，请上传 10MB 以内 PDF", response.getBody().get("message"));
    }
}
