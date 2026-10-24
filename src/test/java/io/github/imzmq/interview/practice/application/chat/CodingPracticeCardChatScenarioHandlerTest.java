package io.github.imzmq.interview.practice.application.chat;

import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.chat.application.contract.ChatSessionOperations;
import io.github.imzmq.interview.chat.application.contract.ChatTaskOperations;
import io.github.imzmq.interview.chat.application.contract.extension.ChatStreamingOperations;
import io.github.imzmq.interview.chat.application.contract.extension.StreamingChatContext;
import io.github.imzmq.interview.common.stream.StreamEventEmitter;
import io.github.imzmq.interview.practice.application.dto.FillPayload;
import io.github.imzmq.interview.practice.application.dto.ScenarioPayload;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingPracticeCardChatScenarioHandlerTest {

    private final ChatSessionOperations sessions = mock(ChatSessionOperations.class);
    private final ChatTaskOperations tasks = mock(ChatTaskOperations.class);
    private final ChatStreamingOperations streaming = mock(ChatStreamingOperations.class);
    private final CodingPracticeCardChatScenarioHandler handler =
            new CodingPracticeCardChatScenarioHandler(sessions, tasks, streaming);

    @Test
    void returnsFalseForNonCodingResult() {
        StreamingChatContext context = context(TaskResponse.ok(Map.of("agent", "OtherAgent")));

        assertThat(handler.handle(context)).isFalse();

        verify(streaming, never()).completeTask(anyString(), any());
    }

    @Test
    void preservesBatchQuizEventsMetadataAndCompletion() {
        Object quizPayload = Map.of("sessionId", "coding-1", "questions", List.of());
        when(streaming.toJson(eq(quizPayload), anyString())).thenReturn("{quiz}");
        RecordingEmitter emitter = new RecordingEmitter();
        StreamingChatContext context = context(TaskResponse.ok(Map.of(
                "agent", "CodingPracticeAgent",
                "status", "batch_quiz",
                "sessionId", "coding-1",
                "quizPayload", quizPayload
        )), emitter);

        assertThat(handler.handle(context)).isTrue();

        assertThat(emitter.types()).containsExactly("progress", "quiz", "finish", "done");
        assertMetadata("coding_practice_batch", "coding-batch-quiz", "coding-1", null, "quiz");
        verify(streaming).completeTask("task-1", emitter);
    }

    @Test
    void preservesScenarioCardEventsMetadataAndCompletion() {
        ScenarioPayload payload = new ScenarioPayload(
                "card-1", "coding-1", "Java", "MEDIUM", "scenario", "题目", "",
                false, false, null, "", "答案", "", "1/2", false, true
        );
        when(streaming.toJson(eq(payload), anyString())).thenReturn("{scenario}");
        RecordingEmitter emitter = new RecordingEmitter();
        StreamingChatContext context = context(TaskResponse.ok(Map.of(
                "agent", "CodingPracticeAgent",
                "status", "scenario_question_generated",
                "sessionId", "coding-1",
                "scenarioPayload", payload
        )), emitter);

        assertThat(handler.handle(context)).isTrue();

        assertThat(emitter.types()).containsExactly("progress", "finish", "done");
        assertMetadata("coding_practice_scenario", "coding-scenario-card", "coding-1", "card-1", "scenario_card");
        verify(streaming).completeTask("task-1", emitter);
    }

    @Test
    void preservesFillCardEventsMetadataAndCompletion() {
        FillPayload payload = new FillPayload(
                "card-2", "coding-2", "JVM", "HARD", "fill", "题目", "",
                false, false, null, "", "答案", "", "1/2", false, true
        );
        when(streaming.toJson(eq(payload), anyString())).thenReturn("{fill}");
        RecordingEmitter emitter = new RecordingEmitter();
        StreamingChatContext context = context(TaskResponse.ok(Map.of(
                "agent", "CodingPracticeAgent",
                "status", "fill_question_generated",
                "sessionId", "coding-2",
                "fillPayload", payload
        )), emitter);

        assertThat(handler.handle(context)).isTrue();

        assertThat(emitter.types()).containsExactly("progress", "finish", "done");
        assertMetadata("coding_practice_fill", "coding-fill-card", "coding-2", "card-2", "fill_card");
        verify(streaming).completeTask("task-1", emitter);
    }

    @Test
    void ownsOrdinaryCodingTextResponse() {
        Map<String, Object> data = Map.of(
                "agent", "CodingPracticeAgent",
                "status", "evaluated",
                "sessionId", "coding-3"
        );
        TaskResponse response = TaskResponse.ok(data);
        when(tasks.extractReplyText(response)).thenReturn("反馈文本");
        when(tasks.shouldClearSession(response)).thenReturn(true);
        RecordingEmitter emitter = new RecordingEmitter();
        StreamingChatContext context = context(response, emitter);

        assertThat(handler.handle(context)).isTrue();

        assertThat(emitter.types()).containsExactly("progress", "finish", "done");
        assertMetadata("coding_practice", "coding-practice", "coding-3", null, "text");
        verify(streaming).sendChunkedText(emitter, "反馈文本", "task-1");
        verify(sessions).clearSessionContext("chat-1", "assistant-1");
        verify(sessions).autoTitleIfNeeded("chat-1", "用户输入");
        verify(streaming).completeTask("task-1", emitter);
    }

    @SuppressWarnings("unchecked")
    private void assertMetadata(String type,
                                String routeLabel,
                                String codingSessionId,
                                String cardId,
                                String contentType) {
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(streaming).updateAssistantPlaceholder(
                eq("assistant-1"), anyString(), metadata.capture(), eq(contentType), eq("COMPLETED")
        );
        assertThat(metadata.getValue())
                .containsEntry("traceId", "trace-1")
                .containsEntry("type", type)
                .containsEntry("routeLabel", routeLabel)
                .containsEntry("routeSource", "routed-chat")
                .containsEntry("codingSessionId", codingSessionId);
        if (cardId != null) {
            assertThat(metadata.getValue()).containsEntry("cardId", cardId);
        }
    }

    private StreamingChatContext context(TaskResponse response) {
        return context(response, new RecordingEmitter());
    }

    private StreamingChatContext context(TaskResponse response, RecordingEmitter emitter) {
        StreamingChatContext context = new StreamingChatContext(
                "chat-1", "user-1", "用户输入", "HYBRID", "trace-1", "task-1", emitter
        );
        context.assistantMessageId("assistant-1");
        context.routeResponse(response);
        return context;
    }

    private record Event(String type, Object payload) {
    }

    private static final class RecordingEmitter implements StreamEventEmitter {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void emit(String eventType, Object payload) {
            events.add(new Event(eventType, payload));
        }

        List<String> types() {
            return events.stream().map(Event::type).toList();
        }
    }
}
