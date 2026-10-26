package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.ChatSessionAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackageClasses = ChatController.class)
public class ChatApiExceptionHandler {

    @ExceptionHandler(ChatSessionAccessException.class)
    public ResponseEntity<Map<String, String>> handleSessionAccess(ChatSessionAccessException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", "CHAT_SESSION_NOT_FOUND",
                "message", "会话不存在"
        ));
    }
}
