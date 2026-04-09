package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地知识图索引读取与校验服务。
 *
 * <p>第一阶段仅负责索引消费，不负责生成。目标是先稳定索引状态和失败语义。</p>
 */
@Service
public class KnowledgeMapService {

    private final KnowledgeRetrievalProperties properties;
    private final ObjectMapper objectMapper;
    private final IngestConfigService ingestConfigService;

    public KnowledgeMapService(KnowledgeRetrievalProperties properties,
                               ObjectMapper objectMapper,
                               IngestConfigService ingestConfigService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.ingestConfigService = ingestConfigService;
    }

    public KnowledgeMapSnapshot loadValidatedIndex() {
        String indexFilePath = resolveIndexFilePath();
        if (indexFilePath == null || indexFilePath.isBlank()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.INDEX_NOT_CONFIGURED,
                    "Local knowledge index path is not configured"
            );
        }

        Path indexPath = Path.of(indexFilePath).toAbsolutePath().normalize();
        if (!Files.exists(indexPath) || !Files.isRegularFile(indexPath)) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.INDEX_NOT_FOUND,
                    "Local knowledge index file not found: " + indexPath
            );
        }

        try {
            JsonNode root = objectMapper.readTree(indexPath.toFile());
            int schemaVersion = root.path("schemaVersion").asInt(root.path("version").asInt(-1));
            if (schemaVersion != properties.getRequiredSchemaVersion()) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.INDEX_SCHEMA_MISMATCH,
                        "Unexpected schemaVersion: " + schemaVersion
                );
            }

            JsonNode nodes = root.path("nodes");
            if (!nodes.isArray() || nodes.isEmpty()) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.INDEX_EMPTY,
                        "Knowledge index has no nodes"
                );
            }

            String buildId = root.path("buildId").asText("");
            String vaultRoot = root.path("vaultRoot").asText("");
            List<KnowledgeNode> parsedNodes = new ArrayList<>();
            for (JsonNode node : nodes) {
                String id = node.path("id").asText("").trim();
                if (id.isBlank()) {
                    continue;
                }
                parsedNodes.add(new KnowledgeNode(
                        id,
                        node.path("title").asText(""),
                        readTextList(node.path("aliases")),
                        node.path("summary").asText(""),
                        readTextList(node.path("tags")),
                        node.path("filePath").asText(""),
                        readTextList(node.path("wikiLinks")),
                        readTextList(node.path("backlinks"))
                ));
            }
            if (parsedNodes.isEmpty()) {
                throw new LocalGraphRetrievalException(
                        LocalGraphFailureReason.INDEX_EMPTY,
                        "Knowledge index has no valid nodes"
                );
            }
            return new KnowledgeMapSnapshot(indexPath, schemaVersion, buildId, vaultRoot, List.copyOf(parsedNodes));
        } catch (LocalGraphRetrievalException e) {
            throw e;
        } catch (IOException e) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.INDEX_LOAD_FAILED,
                    "Failed to load local knowledge index: " + indexPath,
                    e
            );
        }
    }

    private String resolveIndexFilePath() {
        String configured = properties.getIndexFilePath();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String persisted = ingestConfigService.getLocalKnowledgeIndexPath();
        if (persisted != null && !persisted.isBlank()) {
            properties.setIndexFilePath(persisted);
            return persisted;
        }
        return configured;
    }

    public record KnowledgeMapSnapshot(
            Path indexPath,
            int schemaVersion,
            String buildId,
            String vaultRoot,
            List<KnowledgeNode> nodes
    ) {
        public int nodeCount() {
            return nodes == null ? 0 : nodes.size();
        }
    }

    public record KnowledgeNode(
            String id,
            String title,
            List<String> aliases,
            String summary,
            List<String> tags,
            String filePath,
            List<String> wikiLinks,
            List<String> backlinks
    ) {
    }

    private List<String> readTextList(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }
}
