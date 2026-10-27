package io.github.imzmq.interview.learning.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;

import java.io.IOException;

import static io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException.Code.INVALID_INPUT;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningPlanPreviewRequest(
        @JsonDeserialize(using = TextDeserializer.class) String topic,
        @JsonDeserialize(using = TextDeserializer.class) String request) {

    void validate(String key) {
        if (key == null || key.isBlank() || key.length() > 120 || key.chars().anyMatch(Character::isISOControl)
                || topic == null || topic.isBlank() || topic.trim().length() > 200
                || (request != null && request.trim().length() > 2000)) {
            throw new LearningPlanAccessException(INVALID_INPUT);
        }
    }

    /** Do not let Jackson turn numbers/booleans into strings at this public boundary. */
    public static final class TextDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }
}
