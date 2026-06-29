package io.github.imzmq.interview.common.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamTaskManagerTest {

    @Test
    void completeWinsTerminalRaceAndPreventsLaterCancelEvent() {
        StreamTaskManager manager = new StreamTaskManager();
        RecordingEmitter emitter = new RecordingEmitter();
        String taskId = manager.newTaskId();

        manager.register(taskId, emitter);

        assertThat(manager.tryComplete(taskId)).isTrue();
        assertThat(manager.cancel(taskId, "已停止生成")).isFalse();

        assertThat(emitter.events()).isEmpty();
    }

    @Test
    void cancelWinsTerminalRaceAndPreventsLaterFinish() {
        StreamTaskManager manager = new StreamTaskManager();
        RecordingEmitter emitter = new RecordingEmitter();
        String taskId = manager.newTaskId();

        manager.register(taskId, emitter);

        assertThat(manager.cancel(taskId, "已停止生成")).isTrue();
        assertThat(manager.tryComplete(taskId)).isFalse();

        assertThat(emitter.events()).containsExactly("cancel", "done");
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void detachingLifecycleRemovesTaskSoTimedOutStreamsDoNotAccumulate() {
        StreamTaskManager manager = new StreamTaskManager();
        String taskId = manager.newTaskId();

        manager.register(taskId, new RecordingEmitter());
        manager.detach(taskId);

        assertThat(manager.isCancelled(taskId)).isTrue();
        assertThat(manager.cancel(taskId, "已停止生成")).isFalse();
    }

    private static final class RecordingEmitter implements StreamEventEmitter {
        private final List<String> events = new ArrayList<>();
        private boolean completed;

        @Override
        public void emit(String eventType, Object payload) {
            events.add(eventType);
        }

        @Override
        public void complete() {
            completed = true;
        }

        List<String> events() {
            return events;
        }

        boolean completed() {
            return completed;
        }
    }
}
