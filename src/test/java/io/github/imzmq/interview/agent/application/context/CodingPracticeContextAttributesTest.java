package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingPracticeContextAttributesTest {

    @Test
    void textReturnsTrimmedValue() {
        AgentContextQuery query = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.TOPIC, "  数组与字符串  ")
        );

        assertEquals("数组与字符串", CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.TOPIC));
    }

    @Test
    void integerParsesNumberAndStringValues() {
        AgentContextQuery numberQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.COUNT, 3)
        );
        AgentContextQuery stringQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.COUNT, "4")
        );

        assertEquals(3, CodingPracticeContextAttributes.integer(numberQuery, CodingPracticeContextAttributes.COUNT, 1));
        assertEquals(4, CodingPracticeContextAttributes.integer(stringQuery, CodingPracticeContextAttributes.COUNT, 1));
    }

    @Test
    void stringListReadsListAndSingleString() {
        AgentContextQuery listQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.EXCLUDED_TOPICS, List.of("递归", " 图 "))
        );
        AgentContextQuery stringQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.EXCLUDED_TOPICS, " 动态规划 ")
        );

        assertEquals(List.of("递归", "图"), CodingPracticeContextAttributes.stringList(listQuery, CodingPracticeContextAttributes.EXCLUDED_TOPICS));
        assertEquals(List.of("动态规划"), CodingPracticeContextAttributes.stringList(stringQuery, CodingPracticeContextAttributes.EXCLUDED_TOPICS));
        assertTrue(CodingPracticeContextAttributes.stringList(null, CodingPracticeContextAttributes.EXCLUDED_TOPICS).isEmpty());
    }
}
