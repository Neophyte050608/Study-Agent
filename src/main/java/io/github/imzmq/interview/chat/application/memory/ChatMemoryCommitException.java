package io.github.imzmq.interview.chat.application.memory;

public class ChatMemoryCommitException extends RuntimeException {

    private final String code;

    public ChatMemoryCommitException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
