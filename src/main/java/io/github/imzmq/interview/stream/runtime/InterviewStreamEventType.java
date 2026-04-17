package io.github.imzmq.interview.stream.runtime;

public enum InterviewStreamEventType {
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

    InterviewStreamEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

