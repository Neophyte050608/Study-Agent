package io.github.imzmq.interview.service;

import io.github.imzmq.interview.config.knowledge.KnowledgeRetrievalProperties;
import io.github.imzmq.interview.ingestion.application.IngestConfigService;
import io.github.imzmq.interview.knowledge.application.indexing.LocalKnowledgeIndexBuildService;
import io.github.imzmq.interview.knowledge.application.indexing.LocalKnowledgeScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalKnowledgeIndexBuildServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBuildIndexAndActivateConfiguredPath(@TempDir Path tempDir) throws Exception {
        Path vault = Files.createDirectory(tempDir.resolve("vault"));
        Files.writeString(vault.resolve("spring.md"), """
                ---
                aliases:
                  - SpringAlias
                tags: [java, spring]
                summary: Spring Boot local summary
                ---
                # Spring Boot

                这是 Spring Boot 笔记。
                [[Redis]]
                """);
        Files.writeString(vault.resolve("redis.md"), """
                # Redis

                Redis 是缓存中间件。
                """);

        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        IngestConfigService ingestConfigService = mock(IngestConfigService.class);
        when(ingestConfigService.getConfig()).thenReturn(Map.of(
                "paths", vault.toString(),
                "ignoreDirs", ".git"
        ));

        LocalKnowledgeIndexBuildService service = new LocalKnowledgeIndexBuildService(
                new io.github.imzmq.interview.rag.core.NoteLoader(),
                properties,
                ingestConfigService,
                new LocalKnowledgeScopeService(),
                objectMapper
        );

        Path output = tempDir.resolve("generated").resolve("local-index.json");
        LocalKnowledgeIndexBuildService.IndexBuildResult result = service.build(
                new LocalKnowledgeIndexBuildService.IndexBuildRequest(
                        vault.toString(),
                        output.toString(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "",
                        true
                )
        );

        assertEquals(output.toAbsolutePath().normalize().toString(), result.outputPath());
        assertEquals(output.toAbsolutePath().normalize().toString(), properties.getIndexFilePath());
        assertTrue(Files.exists(output));

        JsonNode root = objectMapper.readTree(output.toFile());
        assertEquals(2, root.path("nodeCount").asInt());
        assertEquals(2, root.path("nodes").size());

        JsonNode springNode = findNode(root.path("nodes"), "spring");
        JsonNode redisNode = findNode(root.path("nodes"), "redis");
        assertEquals("Spring Boot", springNode.path("title").asText());
        assertEquals("Redis", redisNode.path("title").asText());
        assertTrue(containsText(springNode.path("aliases"), "SpringAlias"));
        assertTrue(containsText(springNode.path("wikiLinks"), "Redis"));
        assertTrue(containsText(redisNode.path("backlinks"), "Spring Boot"));
    }

    @Test
    void shouldReadCurrentStatusFromGeneratedIndex(@TempDir Path tempDir) throws Exception {
        Path vault = Files.createDirectory(tempDir.resolve("vault"));
        Files.writeString(vault.resolve("note.md"), """
                # Kafka

                Kafka 笔记内容。
                """);

        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        IngestConfigService ingestConfigService = mock(IngestConfigService.class);
        when(ingestConfigService.getConfig()).thenReturn(Map.of(
                "paths", vault.toString(),
                "ignoreDirs", "node_modules,.git"
        ));

        LocalKnowledgeIndexBuildService service = new LocalKnowledgeIndexBuildService(
                new io.github.imzmq.interview.rag.core.NoteLoader(),
                properties,
                ingestConfigService,
                new LocalKnowledgeScopeService(),
                objectMapper
        );

        LocalKnowledgeIndexBuildService.IndexBuildResult result = service.build(
                new LocalKnowledgeIndexBuildService.IndexBuildRequest(
                        "",
                        tempDir.resolve("status-index.json").toString(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "",
                        true
                )
        );

        LocalKnowledgeIndexBuildService.IndexStatus status = service.currentStatus();

        assertEquals(result.outputPath(), status.indexFilePath());
        assertTrue(status.indexExists());
        assertEquals(1, status.nodeCount());
        assertEquals(vault.toAbsolutePath().normalize().toString(), status.vaultRoot());
        assertEquals(vault.toString(), status.configuredVaultPath());
        assertFalse(status.buildId().isBlank());
        assertEquals("node_modules,.git", status.configuredIgnoreDirs());
    }

    @Test
    void shouldBuildIndexFromScopeFile(@TempDir Path tempDir) throws Exception {
        Path vault = Files.createDirectory(tempDir.resolve("vault"));
        Files.createDirectories(vault.resolve("learning").resolve("tech"));
        Files.createDirectories(vault.resolve("daily"));
        Files.createDirectories(vault.resolve("meta"));

        Files.writeString(vault.resolve("learning").resolve("tech").resolve("rag.md"), """
                ---
                title: RAG
                summary: RAG 是一种将检索结果注入上下文的方案
                ---
                # RAG

                [[LLM]]
                """);
        Files.writeString(vault.resolve("daily").resolve("log.md"), """
                # 日记

                今天吃饭睡觉。
                """);
        Files.writeString(vault.resolve("meta").resolve("local-knowledge-scope.yaml"), """
                vault_root: %s
                include_dirs:
                  - learning/tech
                exclude_dirs:
                  - daily
                review_dirs:
                  - learning
                default_ignore_dir_names:
                  - meta
                note_rules:
                  summary_min_length: 20
                index_rules:
                  schema_version: 1
                """.formatted(vault.toString().replace("\\", "/")));

        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        IngestConfigService ingestConfigService = mock(IngestConfigService.class);
        when(ingestConfigService.getConfig()).thenReturn(Map.of(
                "paths", vault.toString(),
                "ignoreDirs", ".git"
        ));

        LocalKnowledgeIndexBuildService service = new LocalKnowledgeIndexBuildService(
                new io.github.imzmq.interview.rag.core.NoteLoader(),
                properties,
                ingestConfigService,
                new LocalKnowledgeScopeService(),
                objectMapper
        );

        Path output = tempDir.resolve("scope-index.json");
        LocalKnowledgeIndexBuildService.IndexBuildResult result = service.build(
                new LocalKnowledgeIndexBuildService.IndexBuildRequest(
                        "",
                        output.toString(),
                        List.of(),
                        List.of(),
                        List.of(),
                        vault.resolve("meta").resolve("local-knowledge-scope.yaml").toString(),
                        true
                )
        );

        assertEquals(1, result.nodeCount());
        assertEquals(List.of("learning/tech"), result.includeDirs());
        JsonNode root = objectMapper.readTree(output.toFile());
        assertEquals(1, root.path("nodes").size());
        assertEquals("learning/tech/rag", root.path("nodes").get(0).path("id").asText());
    }

    private JsonNode findNode(JsonNode nodes, String expectedId) {
        for (JsonNode node : nodes) {
            if (expectedId.equals(node.path("id").asText())) {
                return node;
            }
        }
        throw new AssertionError("Node not found: " + expectedId);
    }

    private boolean containsText(JsonNode arrayNode, String value) {
        for (JsonNode node : arrayNode) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }
}






