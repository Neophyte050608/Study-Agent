package io.github.imzmq.interview.chat.application;

/**
 * Non-disclosing failure for absent sessions and sessions owned by another user.
 */
public class ChatSessionAccessException extends RuntimeException {

    public ChatSessionAccessException() {
        super("会话不存在");
    }
}
