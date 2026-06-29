package io.github.imzmq.interview.knowledge.application.chatstream;

import io.github.imzmq.interview.common.stream.StreamEventType;
import io.github.imzmq.interview.common.stream.StreamEventEmitter;

import java.util.Map;
import java.util.function.BooleanSupplier;

public class ChunkedTextStreamer {

    private final int chunkSize;
    private final long delayMillis;

    public ChunkedTextStreamer() {
        this(32, 20L);
    }

    public ChunkedTextStreamer(int chunkSize, long delayMillis) {
        this.chunkSize = Math.max(1, chunkSize);
        this.delayMillis = Math.max(0L, delayMillis);
    }

    public void stream(StreamEventEmitter emitter, String text, BooleanSupplier shouldStop) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < text.length()) {
            if (shouldStop.getAsBoolean()) {
                return;
            }
            int end = Math.min(text.length(), index + chunkSize);
            emitter.emit(StreamEventType.MESSAGE.value(), Map.of(
                    "channel", "answer",
                    "delta", text.substring(index, end)));
            index = end;
            if (delayMillis <= 0L) {
                continue;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}



