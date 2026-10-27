package io.github.imzmq.interview.learning.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException.Code.INVALID_INPUT;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningPlanRetryRequest(
        @JsonDeserialize(using = RevisionDeserializer.class) Integer expectedRevision,
        @JsonDeserialize(contentUsing = LearningPlanPreviewRequest.TextDeserializer.class) List<String> nodes) {

    Set<String> selectedNodes() {
        if (expectedRevision == null || expectedRevision < 0 || (nodes != null &&
                (nodes.size() > 3 || nodes.stream().anyMatch(id -> id == null || !Set.of("profile", "evidence", "compose").contains(id))
                        || nodes.stream().distinct().count() != nodes.size()))) {
            throw new LearningPlanAccessException(INVALID_INPUT);
        }
        return nodes == null ? Set.of() : Set.copyOf(nodes);
    }

    public static final class RevisionDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
