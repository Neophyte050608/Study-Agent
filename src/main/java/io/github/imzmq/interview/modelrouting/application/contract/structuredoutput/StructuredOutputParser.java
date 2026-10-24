package io.github.imzmq.interview.modelrouting.application.contract.structuredoutput;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parses and validates structured model output while preserving retry metadata.
 */
public interface StructuredOutputParser {

    String extractJson(String raw);

    StructuredOutputResult<JsonNode> parseTree(
            String raw,
            StructuredOutputSchema schema,
            StructuredOutputRetry retry
    );
}
