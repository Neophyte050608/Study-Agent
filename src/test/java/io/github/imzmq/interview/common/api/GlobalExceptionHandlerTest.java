package io.github.imzmq.interview.common.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
    }

    @Test
    void shouldMapBusinessExceptionToNotFound() {
        BusinessException ex = new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        ResponseEntity<?> resp = handler.handleBusinessException(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiResponse<?> body = (ApiResponse<?>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(30101);
    }

    @Test
    void shouldMapBusinessExceptionToBadRequest() {
        BusinessException ex = new BusinessException(ErrorCode.BAD_REQUEST);
        ResponseEntity<?> resp = handler.handleBusinessException(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<?> body = (ApiResponse<?>) resp.getBody();
        assertThat(body.code()).isEqualTo(10001);
    }

    @Test
    void shouldMapBusinessExceptionToInternalError() {
        BusinessException ex = new BusinessException(ErrorCode.INTERNAL_ERROR);
        ResponseEntity<?> resp = handler.handleBusinessException(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void shouldHandleMaxUploadSizeExceeded() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(1024);
        ResponseEntity<?> resp = handler.handleMaxUploadSize(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void shouldHandleIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("test param invalid");
        ResponseEntity<?> resp = handler.handleIllegalArgument(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<?> body = (ApiResponse<?>) resp.getBody();
        assertThat(body.code()).isEqualTo(10001);
    }

    @Test
    void shouldHandleIllegalStateException() {
        IllegalStateException ex = new IllegalStateException("not ready");
        ResponseEntity<?> resp = handler.handleIllegalState(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void shouldHandleGenericExceptionAs500() {
        Exception ex = new RuntimeException("unknown error");
        ResponseEntity<?> resp = handler.handleGenericException(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiResponse<?> body = (ApiResponse<?>) resp.getBody();
        assertThat(body.code()).isEqualTo(90000);
    }

    @Test
    void shouldHandleNoResourceFound() {
        NoResourceFoundException ex = mock(NoResourceFoundException.class);
        ResponseEntity<?> resp = handler.handleNoResourceFound(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturnPlainTextForSseRequest() {
        request.addHeader("Accept", "text/event-stream");
        BusinessException ex = new BusinessException(ErrorCode.MCP_TIMEOUT);
        ResponseEntity<?> resp = handler.handleBusinessException(ex, request);
        assertThat(resp.getHeaders().getContentType().toString()).contains("text/plain");
        assertThat(resp.getBody()).isInstanceOf(String.class);
    }

    @Test
    void shouldReturnJsonForNonSseRequest() {
        BusinessException ex = new BusinessException(ErrorCode.MCP_TIMEOUT);
        ResponseEntity<?> resp = handler.handleBusinessException(ex, request);
        assertThat(resp.getBody()).isInstanceOf(ApiResponse.class);
    }

    @Test
    void businessExceptionShouldPreserveRetryableFlag() {
        BusinessException ex = new BusinessException(ErrorCode.MCP_TIMEOUT);
        assertThat(ex.retryable()).isTrue();
        assertThat(ex.errorCode()).isEqualTo(50501);
    }
}
