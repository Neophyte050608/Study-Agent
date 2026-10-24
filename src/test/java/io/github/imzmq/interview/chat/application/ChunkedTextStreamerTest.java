package io.github.imzmq.interview.chat.application;

import io.github.imzmq.interview.common.stream.StreamEventEmitter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkedTextStreamerTest {

    @Test
    void emitsMessageEventsWithAnswerChannelAndUnchangedChunks() {
        RecordingEmitter emitter = new RecordingEmitter();
        ChunkedTextStreamer streamer = new ChunkedTextStreamer(3, 0L);

        streamer.stream(emitter, "abcdefg", () -> false);

        assertThat(emitter.events()).containsExactly(
                new Event("message", Map.of("channel", "answer", "delta", "abc")),
                new Event("message", Map.of("channel", "answer", "delta", "def")),
                new Event("message", Map.of("channel", "answer", "delta", "g"))
        );
    }

    @Test
    void stopsBeforeEmittingAnotherChunkWhenCancelled() {
        RecordingEmitter emitter = new RecordingEmitter();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.afterEmit(() -> cancelled.set(true));
        ChunkedTextStreamer streamer = new ChunkedTextStreamer(3, 0L);

        streamer.stream(emitter, "abcdef", cancelled::get);

        assertThat(emitter.events()).containsExactly(
                new Event("message", Map.of("channel", "answer", "delta", "abc"))
        );
    }

    @Test
    void emitsNothingForEmptyContentOrPreCancelledStream() {
        RecordingEmitter emitter = new RecordingEmitter();
        ChunkedTextStreamer streamer = new ChunkedTextStreamer(3, 0L);

        streamer.stream(emitter, "", () -> false);
        streamer.stream(emitter, null, () -> false);
        streamer.stream(emitter, "content", () -> true);

        assertThat(emitter.events()).isEmpty();
    }

    private record Event(String type, Object payload) {
    }

    private static final class RecordingEmitter implements StreamEventEmitter {
        private final List<Event> events = new ArrayList<>();
        private Runnable afterEmit = () -> { };

        @Override
        public void emit(String eventType, Object payload) {
            events.add(new Event(eventType, payload));
            afterEmit.run();
        }

        void afterEmit(Runnable action) {
            this.afterEmit = action;
        }

        List<Event> events() {
            return events;
        }
    }
}
