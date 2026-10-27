package io.github.imzmq.interview.agent.infrastructure.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StrictDynamicPlanJsonParserTest {

    private final StrictDynamicPlanJsonParser parser =
            new StrictDynamicPlanJsonParser(new ObjectMapper(), 32 * 1024, 4 * 1024);

    @Test
    void parsesOnlyTheExactPlanObject() {
        DynamicPlanParseResult result = parser.parse("""
                {"schemaVersion":"1","nodes":[
                  {"id":"evidence","capability":"knowledge.evidence.retrieve","dependsOn":[],"arguments":{"limit":4}},
                  {"id":"compose","capability":"learning.plan.compose","dependsOn":["evidence"],"arguments":{}}
                ]}
                """);

        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isEmpty();
        assertThat(result.plan().nodes()).hasSize(2);
        assertThat(result.plan().nodes().getFirst().arguments()).containsEntry("limit", 4);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n{\"schemaVersion\":\"1\",\"nodes\":[]}\n```",
            "prefix {\"schemaVersion\":\"1\",\"nodes\":[]}",
            "{\"schemaVersion\":\"1\",\"schemaVersion\":\"1\",\"nodes\":[]}",
            "{\"schemaVersion\":\"1\",\"nodes\":[],\"extra\":true}",
            "[]",
            "{\"schemaVersion\":1,\"nodes\":[]}",
            "{\"schemaVersion\":\"1\",\"nodes\":\"wrong\"}",
            "{\"schemaVersion\":\"1\",\"nodes\":[]} {\"schemaVersion\":\"1\",\"nodes\":[]}"
    })
    void rejectsNonExactJson(String raw) {
        assertThat(parser.parse(raw))
                .isEqualTo(DynamicPlanParseResult.failure("INVALID_PLAN_JSON"));
    }

    @Test
    void rejectsNullAndBlankInput() {
        assertThat(parser.parse(null))
                .isEqualTo(DynamicPlanParseResult.failure("INVALID_PLAN_JSON"));
        assertThat(parser.parse("  \n\t"))
                .isEqualTo(DynamicPlanParseResult.failure("INVALID_PLAN_JSON"));
    }

    @Test
    void rejectsUtf8PayloadOverThePlanLimit() {
        String raw = "{\"schemaVersion\":\"1\",\"nodes\":[],\"padding\":\""
                + "界".repeat(11_000)
                + "\"}";

        assertThat(parser.parse(raw))
                .isEqualTo(DynamicPlanParseResult.failure("PLAN_TOO_LARGE"));
    }

    @Test
    void rejectsNodeArgumentsOverThePerNodeLimit() {
        String raw = """
                {"schemaVersion":"1","nodes":[
                  {"id":"evidence","capability":"knowledge.evidence.retrieve","dependsOn":[],"arguments":{"query":"%s"}}
                ]}
                """.formatted("x".repeat(4 * 1024));

        assertThat(parser.parse(raw))
                .isEqualTo(DynamicPlanParseResult.failure("PLAN_TOO_LARGE"));
    }
}
