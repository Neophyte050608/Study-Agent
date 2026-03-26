package com.example.interview.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class InterviewSseEmitterSender {
    private final SseEmitter emitter;
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InterviewSseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public void sendEvent(String eventName, Object data) {
        if (closed.get()) {
            return;
        }
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException ex) {
                fail(ex);
            }
        }
    }

    public void complete() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            emitter.complete();
        }
    }

    public void fail(Throwable throwable) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            emitter.completeWithError(throwable);
        }
    }
}
