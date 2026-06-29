package io.github.imzmq.interview.common.stream;

public enum StreamEventType {
    META("meta"),
    PROGRESS("progress"),
    MESSAGE("message"),
    IMAGE("image"),
    FINISH("finish"),
    CANCEL("cancel"),
    ERROR("error"),
    QUIZ("quiz"),
    DONE("done");

    private final String value;

    StreamEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

