package com.example.interview.service.chatstream;

import com.example.interview.stream.InterviewSseEmitterSender;
import com.example.interview.stream.InterviewStreamEventType;

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

    public void stream(InterviewSseEmitterSender sender, String text, BooleanSupplier shouldStop) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < text.length()) {
            if (shouldStop.getAsBoolean()) {
                return;
            }
            int end = Math.min(text.length(), index + chunkSize);
            sender.sendEvent(InterviewStreamEventType.MESSAGE.value(), Map.of(
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
