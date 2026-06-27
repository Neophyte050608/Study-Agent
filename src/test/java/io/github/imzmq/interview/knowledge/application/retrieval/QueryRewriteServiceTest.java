package io.github.imzmq.interview.knowledge.application.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRewriteServiceTest {

    @Test
    void shouldRedactSecretsWhenSummarizingRewriteFailure() throws Exception {
        QueryRewriteService service = new QueryRewriteService(null, null, null);

        String summary = service.summarizeError(new RuntimeException(
                "authorization=Bearer abcdefghijklmnopqrstuvwxyz0123456789 api_key=sk-very-secret-token-1234567890"));

        assertTrue(summary.contains("***"));
        assertFalse(summary.contains("abcdefghijklmnopqrstuvwxyz0123456789"));
        assertFalse(summary.contains("sk-very-secret-token-1234567890"));
    }
}
