package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalCandidateRecallServiceTest {

    @Test
    void shouldPrioritizeTitleAndAliasMatches() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setCandidateRecallTopN(2);
        LocalCandidateRecallService service = new LocalCandidateRecallService(properties);

        KnowledgeMapService.KnowledgeMapSnapshot snapshot = new KnowledgeMapService.KnowledgeMapSnapshot(
                Path.of("knowledge_map.json"),
                1,
                "build-1",
                "vault",
                List.of(
                        new KnowledgeMapService.KnowledgeNode("n1", "缓存雪崩", List.of("Redis缓存雪崩"), "热点 key 同时失效", List.of("redis"), "redis/avalanche.md", List.of(), List.of()),
                        new KnowledgeMapService.KnowledgeNode("n2", "HashMap", List.of("哈希表"), "Java 哈希表实现", List.of("java"), "java/hashmap.md", List.of(), List.of()),
                        new KnowledgeMapService.KnowledgeNode("n3", "缓存穿透", List.of(), "请求落到数据库", List.of("redis"), "redis/penetration.md", List.of(), List.of())
                )
        );

        List<KnowledgeMapService.KnowledgeNode> recalled = service.recall("Redis缓存雪崩怎么处理", snapshot);

        assertEquals(2, recalled.size());
        assertEquals("n1", recalled.getFirst().id());
        assertEquals("n3", recalled.get(1).id());
    }
}
