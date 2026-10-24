package io.github.imzmq.interview.modelrouting.application.contract.structuredoutput;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputContractTest {

    @Test
    void success_defensivelyCopiesWarningsAndWithWarningReturnsNewResult() {
        List<String> warnings = new ArrayList<>();
        warnings.add("first");

        StructuredOutputResult<String> original =
                StructuredOutputResult.success("value", 2, warnings);
        warnings.add("external mutation");
        StructuredOutputResult<String> enriched = original.withWarning("second");

        assertThat(original.warnings()).containsExactly("first");
        assertThat(enriched.warnings()).containsExactly("first", "second");
        assertThatThrownBy(() -> enriched.warnings().add("third"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failure_usesStableFallbackAndEmptyPayload() {
        StructuredOutputResult<String> result = StructuredOutputResult.failure(null, 3);

        assertThat(result.success()).isFalse();
        assertThat(result.data()).isNull();
        assertThat(result.failureReason()).isEqualTo("未知错误");
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void schemaBuilder_preservesRequiredTypeDefaultAndAllowedValues() {
        StructuredOutputSchema schema = StructuredOutputSchema.builder()
                .required("taskType", "confidence")
                .type("confidence", StructuredOutputSchema.JsonType.NUMBER, 0.0)
                .allowed("taskType", "CODING", "KNOWLEDGE_QA")
                .build();

        assertThat(schema.requiredFields()).containsExactly("taskType", "confidence");
        assertThat(schema.fieldTypes())
                .containsEntry("confidence", StructuredOutputSchema.JsonType.NUMBER);
        assertThat(schema.fieldDefaults()).containsEntry("confidence", 0.0);
        assertThat(schema.fieldAllowedValues().get("taskType"))
                .containsExactly("CODING", "KNOWLEDGE_QA");
    }
}
