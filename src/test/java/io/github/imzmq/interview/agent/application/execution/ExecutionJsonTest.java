package io.github.imzmq.interview.agent.application.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionJsonTest {
    private final ExecutionJson json = new ExecutionJson(new ObjectMapper());
    @Test void canonicalizesNestedObjectsWithoutChangingArrayOrder() {
        assertThat(json.check("{\"b\":2,\"a\":[{\"z\":0,\"c\":1}]}", 100))
                .isEqualTo(json.check("{\"a\":[{\"c\":1,\"z\":0}],\"b\":2}", 100));
    }
    @Test void rejectsNullEmptyTrailingAndOversizedJson() {
        for (String value : new String[]{"null", "", "{} {}", "\"汉字\""}) {
            assertThatThrownBy(() -> json.check(value, 5)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
