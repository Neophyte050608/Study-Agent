package io.github.imzmq.interview.learning.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableExecutionOperations;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableTaskView;
import io.github.imzmq.interview.config.security.SecurityConfig;
import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import io.github.imzmq.interview.learning.application.planning.DurableLearningPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real SecurityFilterChain and HMAC decoder; only durable storage/runtime is mocked. */
class LearningPlanPreviewSecurityTest {
    private static final String BASE = "/api/learning/plan-previews";
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECRET = "preview_test_only_key_32_bytes_long_not_a_real_secret";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private DurableExecutionOperations execution;

    @BeforeEach
    void setUp() {
        start("jwt");
    }

    private void start(String mode) {
        if (context != null) context.close();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "app.security.mode", mode, "app.security.jwt-secret", SECRET)));
        context.register(WebConfig.class);
        context.refresh();
        execution = context.getBean(DurableExecutionOperations.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void unauthenticatedRequestsNeverReachApplication() throws Exception {
        mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "alice")).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE + "/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).header("Idempotency-Key", "key").contentType("application/json")
                .content("{\"topic\":\"Java\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + ID + "/retry").contentType("application/json")
                .content("{\"expectedRevision\":0}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(execution);
    }

    @Test
    void validSignedJwtOwnerCannotBeReplacedByHeaderOrPayload() throws Exception {
        when(execution.submit(eq("subject:alice"), eq("LEARNING_PLAN_PREVIEW"), eq(1), eq("key"), anyString()))
                .thenReturn(task());
        var result = mvc.perform(post(BASE).header("Authorization", bearer("alice"))
                        .header("X-User-Id", "bob").header("Idempotency-Key", "key")
                        .contentType("application/json").content("{\"topic\":\"Java\",\"userId\":\"bob\",\"owner\":\"bob\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        verify(execution).submit("subject:alice", "LEARNING_PLAN_PREVIEW", 1, "key", "{\"topic\":\"Java\",\"request\":\"\"}");
    }

    @Test
    void invalidSignatureAndExpiredJwtAreRejectedByRealDecoder() throws Exception {
        for (String token : new String[]{token("alice", SECRET + "wrong", Instant.now().plusSeconds(300)),
                token("alice", SECRET, Instant.now().minusSeconds(300)), "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSJ9.", "not-a-token"}) {
            mvc.perform(get(BASE + "/" + ID).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(execution);
    }

    @Test
    void otherOwnerGetsSameNotFoundForQueryRetryAndDelete() throws Exception {
        when(execution.query("subject:alice", ID)).thenReturn(task());
        when(execution.query("subject:bob", ID)).thenThrow(new IllegalArgumentException("EXECUTION_NOT_FOUND"));
        mvc.perform(get(BASE + "/" + ID).header("Authorization", bearer("alice"))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + ID).header("Authorization", bearer("bob")).header("X-User-Id", "alice"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));
        mvc.perform(post(BASE + "/" + ID + "/retry").header("Authorization", bearer("bob"))
                        .contentType("application/json").content("{\"expectedRevision\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/" + ID).header("Authorization", bearer("bob"))).andExpect(status().isNotFound());
        verify(execution, never()).retry(any(), any(), anyInt(), any());
        verify(execution, never()).delete(any(), any());
    }

    @Test
    void rejectsSubjectsThatWouldCollapseAfterIdentitySanitization() throws Exception {
        for (String subject : new String[]{"a".repeat(64) + "1", "a".repeat(64) + "2", "alice\t", " alice ", " "}) {
            mvc.perform(get(BASE + "/" + ID).header("Authorization", bearer(subject)))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(execution);
    }

    @Test
    void openModeIsOneLocalOwnerAndDoesNotCreateSession() throws Exception {
        start("open");
        when(execution.query("default-user", ID)).thenReturn(task());
        var result = mvc.perform(get(BASE + "/" + ID).header("X-User-Id", "pretend-other-user"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        verify(execution).query("default-user", ID);
    }

    @Test
    void disabledSubmissionsReturn503ButReadsRemainAvailable() throws Exception {
        when(execution.submit(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("EXECUTION_DISABLED"));
        when(execution.query("subject:alice", ID)).thenReturn(task());
        mvc.perform(post(BASE).header("Authorization", bearer("alice")).header("Idempotency-Key", "key")
                        .contentType("application/json").content("{\"topic\":\"Java\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EXECUTION_DISABLED"));
        mvc.perform(get(BASE + "/" + ID).header("Authorization", bearer("alice"))).andExpect(status().isOk());
    }

    private DurableTaskView task() {
        var node = new DurableTaskView.NodeView("PENDING", 0, "PENDING");
        return new DurableTaskView(ID, "QUEUED", 0, null, null,
                Map.of("profile", node, "evidence", node, "compose", node), "LEARNING_PLAN_PREVIEW", 1);
    }

    private String bearer(String subject) {
        return "Bearer " + token(subject, SECRET, Instant.now().plusSeconds(300));
    }

    private String token(String subject, String key, Instant expiry) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key.getBytes(StandardCharsets.UTF_8)));
        var claims = JwtClaimsSet.builder().subject(subject).issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, UserIdentityResolver.class, DurableLearningPlanService.class,
            LearningPlanPreviewController.class, LearningPlanApiExceptionHandler.class})
    static class WebConfig {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean DurableExecutionOperations execution() { return mock(DurableExecutionOperations.class); }
    }
}
