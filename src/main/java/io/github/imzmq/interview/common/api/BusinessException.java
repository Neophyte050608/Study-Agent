package io.github.imzmq.interview.common.api;

/**
 * 统一业务异常，携带 ErrorCode 和可重试标识。
 */
public class BusinessException extends RuntimeException {

    private final int errorCode;
    private final boolean retryable;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage(), null);
    }

    public BusinessException(ErrorCode code, String detail) {
        this(code, detail, null);
    }

    public BusinessException(ErrorCode code, Throwable cause) {
        this(code, code.defaultMessage(), cause);
    }

    public BusinessException(ErrorCode code, String detail, Throwable cause) {
        super(join(code.defaultMessage(), detail), cause);
        this.errorCode = code.code();
        this.retryable = code.retryable();
    }

    private static String join(String defaultMessage, String detail) {
        if (detail == null || detail.isBlank()) return defaultMessage;
        return defaultMessage + ": " + detail;
    }

    public int errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
}
