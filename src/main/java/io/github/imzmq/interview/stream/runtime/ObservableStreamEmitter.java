package io.github.imzmq.interview.stream.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ObservableStreamEmitter implements StreamEventEmitter {

    private final StreamEventEmitter primary;
    private final CopyOnWriteArrayList<StreamEventEmitter> observers = new CopyOnWriteArrayList<>();

    public ObservableStreamEmitter(StreamEventEmitter primary) {
        this.primary = primary;
    }

    public ObservableStreamEmitter addObserver(StreamEventEmitter observer) {
        if (observer != null && observer != primary) {
            observers.addIfAbsent(observer);
        }
        return this;
    }

    public List<StreamEventEmitter> observers() {
        return List.copyOf(observers);
    }

    @Override
    public void emit(String eventType, Object payload) {
        primary.emit(eventType, payload);
        for (StreamEventEmitter observer : observers) {
            try {
                observer.emit(eventType, payload);
            } catch (RuntimeException ignored) {
                // Best-effort observers must not break the primary stream.
            }
        }
    }

    @Override
    public void complete() {
        primary.complete();
        for (StreamEventEmitter observer : observers) {
            try {
                observer.complete();
            } catch (RuntimeException ignored) {
                // Ignore secondary observer shutdown failures.
            }
        }
    }

    @Override
    public void fail(Throwable throwable) {
        primary.fail(throwable);
        for (StreamEventEmitter observer : observers) {
            try {
                observer.fail(throwable);
            } catch (RuntimeException ignored) {
                // Ignore secondary observer failure hooks.
            }
        }
    }
}

