package com.example.interview.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class InterviewSseEmitterSender implements StreamEventEmitter {
    private final SseEmitter emitter;
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InterviewSseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void emit(String eventName, Object data) {
        sendEvent(eventName, data);
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
                closeSilently();
            }
        }
    }

    @Override
    public void complete() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Async request may already be in terminal state after disconnect.
            }
        }
    }

    @Override
    public void fail(Throwable throwable) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            try {
                emitter.completeWithError(throwable);
            } catch (IllegalStateException ignored) {
                // Container may have already finished async error handling.
            }
        }
    }

    private void closeSilently() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Ignore container async lifecycle races after client disconnect.
            }
        }
    }
}
