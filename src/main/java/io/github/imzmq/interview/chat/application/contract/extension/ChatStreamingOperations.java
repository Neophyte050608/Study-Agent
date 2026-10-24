package io.github.imzmq.interview.chat.application.contract.extension;

import io.github.imzmq.interview.common.stream.StreamEventEmitter;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Streaming capabilities available to business-owned chat scenario handlers. */
public interface ChatStreamingOperations {

    void updateAssistantPlaceholder(String messageId, String content, Map<String, Object> metadata,
                                    String contentType, String generationStatus);

    void sendChunkedText(StreamEventEmitter emitter, String text, String taskId);

    <T> T streamObservedAnswer(StreamEventEmitter emitter, String taskId,
                               Function<java.util.function.Consumer<String>, T> producer);

    void sendImageEvents(StreamEventEmitter emitter, List<?> images, String taskId);

    String buildRichContent(String text, List<?> images);

    String toJson(Object value, String fallbackText);

    boolean isCancelled(String taskId);

    void completeTask(String taskId, StreamEventEmitter emitter);
}
