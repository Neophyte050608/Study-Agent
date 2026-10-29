package io.github.imzmq.interview.chat.application.memory;

/**
 * Stable, non-disclosing application error for memory lifecycle governance.
 */
public final class ChatMemoryManagementException extends RuntimeException {

    private final String code;

    public ChatMemoryManagementException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
