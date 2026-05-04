package io.github.imzmq.interview.chat.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmJsonParserTest {

    private LlmJsonParser parser;

    @BeforeEach
    void setUp() {
        parser = new LlmJsonParser(new ObjectMapper());
    }

    // === Layer 1: 提取 ===

    @Test
    void extract_pureJson_returnsDirectly() {
        String raw = "{\"taskType\":\"CODING\",\"confidence\":0.9}";
        String extracted = parser.extractJson(raw);
        assertThat(extracted).isEqualTo(raw);
    }

    @Test
    void extract_markdownFenceWithLang_stripsFence() {
        String raw = "```json\n{\"taskType\":\"CODING\"}\n```";
        String extracted = parser.extractJson(raw);
        assertThat(extracted).isEqualTo("{\"taskType\":\"CODING\"}");
    }

    @Test
    void extract_markdownFenceWithoutLang_stripsFence() {
        String raw = "```\n{\"taskType\":\"CODING\"}\n```";
        String extracted = parser.extractJson(raw);
        assertThat(extracted).isEqualTo("{\"taskType\":\"CODING\"}");
    }

    @Test
    void extract_jsonInMixedText_findsBalancedBraces() {
        String raw = "好的，分类结果如下：\n{\"taskType\":\"CODING\",\"intentId\":\"coding.practice\"}\n请确认。";
        String extracted = parser.extractJson(raw);
        assertThat(extracted).isEqualTo("{\"taskType\":\"CODING\",\"intentId\":\"coding.practice\"}");
    }

    @Test
    void extract_nestedBraces_handlesCorrectly() {
        String raw = "{\"slots\":{\"topic\":\"Java\"},\"confidence\":0.8}";
        String extracted = parser.extractJson(raw);
        assertThat(extracted).contains("\"topic\":\"Java\"");
    }

    @Test
    void extract_noJson_returnsNull() {
        String raw = "这是一段纯文本回复，没有任何JSON";
        String extracted = parser.extractJson(raw);
        assertThat(extracted).isNull();
    }

    // === Layer 2: 修复 ===

    @Test
    void repair_trailingCommaInObject_removes() {
        String broken = "{\"a\":1,\"b\":2,}";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    void repair_trailingCommaInArray_removes() {
        String broken = "[1,2,3,]";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).isEqualTo("[1,2,3]");
    }

    @Test
    void repair_singleQuotes_convertsToDouble() {
        String broken = "{'taskType':'CODING'}";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).isEqualTo("{\"taskType\":\"CODING\"}");
    }

    @Test
    void repair_unquotedKeys_addsQuotes() {
        String broken = "{taskType:\"CODING\",count:5}";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).contains("\"taskType\"");
        assertThat(repaired).contains("\"count\"");
    }

    @Test
    void repair_lineComments_removes() {
        String broken = "{\n// this is a comment\n\"a\":1\n}";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).doesNotContain("//");
        assertThat(repaired).contains("\"a\":1");
    }

    @Test
    void repair_blockComments_removes() {
        String broken = "{\"a\":1/* inline comment */,\"b\":2}";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    void repair_truncated_closesOpenBraces() {
        String broken = "{\"a\":1,\"b\":{\"c\":2}";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).endsWith("}");
    }

    @Test
    void repair_concatenatedObjects_wrapsInArray() {
        String broken = "{\"a\":1}{\"b\":2}";
        String repaired = parser.repairJson(broken);
        assertThat(repaired).startsWith("[");
        assertThat(repaired).endsWith("]");
        assertThat(repaired).contains("},{");
    }
}
