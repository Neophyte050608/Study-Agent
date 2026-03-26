package com.example.interview.stream;

public enum InterviewStreamEventType {
    META("meta"),
    PROGRESS("progress"),
    MESSAGE("message"),
    FINISH("finish"),
    CANCEL("cancel"),
    ERROR("error"),
    DONE("done");

    private final String value;

    InterviewStreamEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
