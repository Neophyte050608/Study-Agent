package io.github.imzmq.interview.routing.application.understanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.chat.application.LlmJsonParser;
import io.github.imzmq.interview.routing.application.contract.result.DecisionSource;
import io.github.imzmq.interview.routing.application.contract.result.TurnRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRecordMapperTest {

    private IntentRecordMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new IntentRecordMapper(new LlmJsonParser(new ObjectMapper()));
    }

    @Test
    void mapsMarkdownWrappedSingleTaskAndStringSlots() {
        var record = mapper.map("s1", "那 Redis 呢", """
                ```json
                {"relation":"FOLLOW_UP","standaloneQuery":"Redis 的持久化机制是什么", "tasks":[
                  {"intentLeafId":"KNOWLEDGE_QA","slots":{"topic":"Redis","count":2},"score":0.91}
                ],"confidence":0.87,"reason":"延续追问"}
                ```
                """);

        assertThat(record.relation()).isEqualTo(TurnRelation.FOLLOW_UP);
        assertThat(record.tasks()).hasSize(1);
        assertThat(record.tasks().getFirst().slots()).containsEntry("topic", "Redis").containsEntry("count", "2");
        assertThat(record.source()).isEqualTo(DecisionSource.LLM);
    }

    @Test
    void keepsOnlyFirstTaskDuringM1() {
        var record = mapper.map("s", "复合请求", """
                {"relation":"NEW_TOPIC","standaloneQuery":"复合请求","tasks":[
                  {"intentLeafId":"A","slots":{},"score":0.9},
                  {"intentLeafId":"B","slots":{},"score":0.8}
                ],"confidence":0.9,"reason":"two"}
                """);

        assertThat(record.tasks()).extracting(task -> task.intentLeafId()).containsExactly("A");
    }

    @Test
    void returnsFallbackForInvalidRelationMissingFieldsAndInvalidScore() {
        assertFallback(mapper.map("s", "raw", "{\"relation\":\"UNKNOWN\",\"standaloneQuery\":\"x\",\"tasks\":[],\"confidence\":0.5,\"reason\":\"x\"}"));
        assertFallback(mapper.map("s", "raw", "{\"relation\":\"NEW_TOPIC\"}"));
        assertFallback(mapper.map("s", "raw", "{\"relation\":\"NEW_TOPIC\",\"standaloneQuery\":\"x\",\"tasks\":[{\"intentLeafId\":\"A\",\"slots\":{},\"score\":2}],\"confidence\":0.5,\"reason\":\"x\"}"));
        assertFallback(mapper.map("s", "raw", "not json"));
    }

    private void assertFallback(io.github.imzmq.interview.routing.application.contract.result.IntentRecord record) {
        assertThat(record.source()).isEqualTo(DecisionSource.FALLBACK_RULE);
        assertThat(record.standaloneQuery()).isEqualTo("raw");
        assertThat(record.tasks()).isEmpty();
    }
}
