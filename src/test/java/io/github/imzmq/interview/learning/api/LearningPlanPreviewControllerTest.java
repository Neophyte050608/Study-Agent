package io.github.imzmq.interview.learning.api;

import io.github.imzmq.interview.common.api.GlobalExceptionHandler;
import io.github.imzmq.interview.identity.application.contract.UserIdentityQuery;
import io.github.imzmq.interview.learning.application.contract.DurableLearningPlanOperations;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;
import io.github.imzmq.interview.learning.application.contract.result.DurableLearningPlanView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningPlanPreviewControllerTest {
    private static final String BASE = "/api/learning/plan-previews";
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private final DurableLearningPlanOperations operations = mock(DurableLearningPlanOperations.class);
    private final UserIdentityQuery identity = mock(UserIdentityQuery.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LearningPlanPreviewController(operations, identity))
                .setControllerAdvice(new GlobalExceptionHandler(), new LearningPlanApiExceptionHandler()).build();
        when(identity.resolveTrusted(eq("alice"), any())).thenReturn("subject:alice");
    }

    @Test
    void submitsWithTrustedOwnerAndIgnoresSelfReportedIdentity() throws Exception {
        when(operations.submit("subject:alice", "key", "Java", "three days")).thenReturn(view());
        for (int duplicate = 0; duplicate < 2; duplicate++) {
            mvc.perform(post(BASE).principal(() -> "alice").header("Idempotency-Key", "key")
                            .header("X-User-Id", "forged").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"topic\":\"Java\",\"request\":\"three days\",\"owner\":\"forged\",\"context\":{\"userId\":\"forged\"}}"))
                    .andExpect(status().isAccepted()).andExpect(header().string("Location", BASE + "/" + ID))
                    .andExpect(jsonPath("$.taskId").value(ID)).andExpect(jsonPath("$.revision").value(0))
                    .andExpect(jsonPath("$.resultJson").doesNotExist()).andExpect(jsonPath("$.owner").doesNotExist());
        }
        verify(operations, times(2)).submit("subject:alice", "key", "Java", "three days");
        verify(identity, never()).resolve(any(), any(), any());
    }

    @Test
    void queriesRetriesAndDeletesOnlyThroughLearningContract() throws Exception {
        when(operations.query("subject:alice", ID)).thenReturn(view());
        when(operations.retry("subject:alice", ID, 0, Set.of("evidence"))).thenReturn(view());
        when(operations.retry("subject:alice", ID, 0, Set.of())).thenReturn(view());
        mvc.perform(get(BASE + "/" + ID).principal(() -> "alice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.profile.attempts").value(0));
        mvc.perform(post(BASE + "/" + ID + "/retry").principal(() -> "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"nodes\":[\"evidence\"]}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", BASE + "/" + ID));
        mvc.perform(post(BASE + "/" + ID + "/retry").principal(() -> "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0}"))
                .andExpect(status().isAccepted());
        mvc.perform(delete(BASE + "/" + ID).principal(() -> "alice"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(operations).delete("subject:alice", ID);
        verify(operations).retry("subject:alice", ID, 0, Set.of("evidence"));
        verify(operations).retry("subject:alice", ID, 0, Set.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"topic\":42}", "{\"topic\":\" \"}", "{\"topic\":\"Java\",\"request\":{}}"})
    void rejectsInvalidSubmissionBodies(String json) throws Exception {
        mvc.perform(post(BASE).principal(() -> "alice").header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(operations);
    }

    @Test
    void rejectsMissingOrOversizedKeyAndFieldLimits() throws Exception {
        mvc.perform(post(BASE).principal(() -> "alice").contentType(MediaType.APPLICATION_JSON).content("{\"topic\":\"Java\"}"))
                .andExpect(status().isBadRequest());
        for (String key : new String[]{"", " ", "k".repeat(121), "k\t"}) {
            mvc.perform(post(BASE).principal(() -> "alice").header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"topic\":\"Java\"}"))
                    .andExpect(status().isBadRequest());
        }
        for (String json : new String[]{"{\"topic\":\"" + "x".repeat(201) + "\"}",
                "{\"topic\":\"Java\",\"request\":\"" + "x".repeat(2001) + "\"}"}) {
            mvc.perform(post(BASE).principal(() -> "alice").header("Idempotency-Key", "key")
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(operations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"expectedRevision\":null}", "{\"expectedRevision\":-1}",
            "{\"expectedRevision\":1.5}", "{\"expectedRevision\":\"1\"}", "{\"expectedRevision\":2147483648}",
            "{\"expectedRevision\":0,\"nodes\":[\"unknown\"]}", "{\"expectedRevision\":0,\"nodes\":[null]}",
            "{\"expectedRevision\":0,\"nodes\":[\"profile\",\"profile\"]}", "{\"expectedRevision\":0,\"nodes\":\"profile\"}"})
    void rejectsInvalidRetryBody(String json) throws Exception {
        mvc.perform(post(BASE + "/" + ID + "/retry").principal(() -> "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(operations);
    }

    @Test
    void rejectsMissingTrustedIdentityAndMalformedTaskId() throws Exception {
        mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "forged"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(BASE + "/bad").principal(() -> "alice")).andExpect(status().isBadRequest());
        verifyNoInteractions(operations);
    }

    @ParameterizedTest
    @CsvSource({"EXECUTION_NOT_FOUND,404", "IDEMPOTENCY_CONFLICT,409", "REVISION_CONFLICT,409",
            "EXECUTION_NOT_RETRYABLE,409", "EXECUTION_NOT_TERMINAL,409", "RETRY_ROOT_NOT_FAILED,409",
            "ATTEMPT_BUDGET_EXHAUSTED,409", "EXECUTION_DISABLED,503", "EXECUTION_UNAVAILABLE,503",
            "INVALID_EXECUTION_RESULT,500", "INTERNAL_ERROR,500", "INVALID_PREVIEW_TOPIC,400"})
    void mapsOnlySafeErrorContract(String code, int status) throws Exception {
        doThrow(new LearningPlanAccessException(LearningPlanAccessException.Code.valueOf(code)))
                .when(operations).query("subject:alice", ID);
        mvc.perform(get(BASE + "/" + ID).principal(() -> "alice"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void scopedAdviceDoesNotExposeUnexpectedErrors() throws Exception {
        when(operations.query("subject:alice", ID)).thenThrow(new IllegalStateException("password=secret"));
        mvc.perform(get(BASE + "/" + ID).principal(() -> "alice"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    private DurableLearningPlanView view() {
        return new DurableLearningPlanView(ID, "QUEUED", 0, null, null,
                Map.of("profile", new DurableLearningPlanView.NodeView("PENDING", 0, "PENDING")));
    }
}
