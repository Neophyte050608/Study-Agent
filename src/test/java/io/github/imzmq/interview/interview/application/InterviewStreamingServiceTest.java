package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.common.stream.StreamEventEmitter;
import io.github.imzmq.interview.common.stream.StreamTaskManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewStreamingServiceTest {

    @Test
    void sendChunkedStopsBeforeSendingRemainingChunksAfterCancellation() throws Exception {
        StreamTaskManager taskManager = new StreamTaskManager();
        InterviewStreamingService service = new InterviewStreamingService(null, taskManager, Runnable::run, 1000L, 2);
        String taskId = taskManager.newTaskId();
        CancellingEmitter emitter = new CancellingEmitter(taskManager, taskId);
        taskManager.register(taskId, emitter);

        Method sendChunked = InterviewStreamingService.class.getDeclaredMethod(
                "sendChunked", String.class, StreamEventEmitter.class, String.class, String.class);
        sendChunked.setAccessible(true);
        sendChunked.invoke(service, taskId, emitter, "question", "abcdefgh");

        assertThat(emitter.events().stream().filter("message"::equals).count()).isEqualTo(1L);
    }

    private static final class CancellingEmitter implements StreamEventEmitter {
        private final StreamTaskManager taskManager;
        private final String taskId;
        private final List<String> events = new ArrayList<>();

        private CancellingEmitter(StreamTaskManager taskManager, String taskId) {
            this.taskManager = taskManager;
            this.taskId = taskId;
        }

        @Override
        public void emit(String eventType, Object payload) {
            events.add(eventType);
            taskManager.cancel(taskId, "stop");
        }

        List<String> events() {
            return events;
        }
    }
}
