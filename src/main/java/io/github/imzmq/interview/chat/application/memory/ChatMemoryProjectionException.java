package io.github.imzmq.interview.chat.application.memory;

public class ChatMemoryProjectionException extends RuntimeException {

    private final String code;

    public ChatMemoryProjectionException(String code) {
        super(code);
        this.code = code;
    }

    public ChatMemoryProjectionException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
