package io.github.imzmq.interview.knowledge.application.observability;

import io.github.imzmq.interview.common.stream.SseStreamEventEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RagTraceEventBusTest {

    @Test
    void unsubscribeDoesNotDropConcurrentSubscriberWhenLastOldSubscriberLeaves() throws Exception {
        RagTraceEventBus eventBus = new RagTraceEventBus();
        String traceId = "trace-1";
        SseStreamEventEmitter first = new SseStreamEventEmitter(new SseEmitter());
        SseStreamEventEmitter second = new SseStreamEventEmitter(new SseEmitter());
        BlockingEmptyList list = new BlockingEmptyList(first);
        subscriberMap(eventBus).put(traceId, list);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> eventBus.unsubscribe(traceId, first));
            assertThat(list.awaitIsEmptyCheck()).isTrue();

            executor.submit(() -> eventBus.subscribe(traceId, second));
            assertThat(list.awaitAddAttempt()).isFalse();

            list.releaseIsEmptyCheck();
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(subscriberMap(eventBus).get(traceId)).containsExactly(second);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, CopyOnWriteArrayList<SseStreamEventEmitter>> subscriberMap(RagTraceEventBus eventBus) throws Exception {
        Field subscribersField = RagTraceEventBus.class.getDeclaredField("subscribers");
        subscribersField.setAccessible(true);
        return (ConcurrentHashMap<String, CopyOnWriteArrayList<SseStreamEventEmitter>>) subscribersField.get(eventBus);
    }

    private static final class BlockingEmptyList extends CopyOnWriteArrayList<SseStreamEventEmitter> {
        private final CountDownLatch isEmptyEntered = new CountDownLatch(1);
        private final CountDownLatch releaseIsEmpty = new CountDownLatch(1);
        private final CountDownLatch addAttempted = new CountDownLatch(1);
        private volatile boolean countAdds;

        private BlockingEmptyList(SseStreamEventEmitter initialSubscriber) {
            super.add(initialSubscriber);
            countAdds = true;
        }

        @Override
        public boolean isEmpty() {
            if (super.isEmpty()) {
                isEmptyEntered.countDown();
                try {
                    releaseIsEmpty.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.isEmpty();
        }

        @Override
        public boolean add(SseStreamEventEmitter sender) {
            if (countAdds) {
                addAttempted.countDown();
            }
            return super.add(sender);
        }

        boolean awaitIsEmptyCheck() throws InterruptedException {
            return isEmptyEntered.await(2, TimeUnit.SECONDS);
        }

        boolean awaitAddAttempt() throws InterruptedException {
            return addAttempted.await(200, TimeUnit.MILLISECONDS);
        }

        void releaseIsEmptyCheck() {
            releaseIsEmpty.countDown();
        }
    }
}
