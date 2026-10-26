package io.github.imzmq.interview.chat.application.memory;

public class ChatMemoryExtractionException extends RuntimeException {

    private final String code;

    public ChatMemoryExtractionException(String code) {
        super(code);
        this.code = code;
    }

    public ChatMemoryExtractionException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
