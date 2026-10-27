package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saber 风格 chunk 级黄金评测集解析器。
 *
 * <p>输入为 JSONL(每行一个 JSON 对象)：</p>
 * <ul>
 *     <li>{@code gold.txt} 行 -> {@link GoldSetCase}</li>
 *     <li>{@code test.txt} 行 -> {@link GoldSetChunk}</li>
 * </ul>
 *
 * <p>本类只做纯解析，不触达向量库/数据库/网络，可在无任何 API Key 的环境下运行，
 * 作为 chunk 级检索评测的数据入口。纯空行会被跳过；任何非空坏行都会携带物理行号失败，
 * 避免静默丢失样本后生成误导性的评测报告。</p>
 */
public final class GoldSetDataLoader {

    private final ObjectMapper objectMapper;

    /**
     * 使用默认 ObjectMapper 构造。
     */
    public GoldSetDataLoader() {
        this(new ObjectMapper());
    }

    /**
     * 使用外部 ObjectMapper 构造，便于与 Spring 容器共享配置。
     *
     * @param objectMapper JSON 映射器
     */
    public GoldSetDataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 解析 gold.txt 风格的黄金用例集。
     *
     * @param reader JSONL 文本读取器
     * @return 黄金用例列表，顺序与文件一致
     * @throws IOException 读取失败时抛出
     */
    public List<GoldSetCase> loadGoldCases(Reader reader) throws IOException {
        List<GoldSetCase> cases = new ArrayList<>();
        forEachJsonLine(reader, (node, lineNumber) ->
                cases.add(parseGoldCase(node, lineNumber)));
        return cases;
    }

    /**
     * 解析 test.txt 风格的语料库 chunk 集。
     *
     * @param reader JSONL 文本读取器
     * @return chunk 列表，顺序与文件一致
     * @throws IOException 读取失败时抛出
     */
    public List<GoldSetChunk> loadChunks(Reader reader) throws IOException {
        List<GoldSetChunk> chunks = new ArrayList<>();
        forEachJsonLine(reader, (node, lineNumber) ->
                chunks.add(parseChunk(node, lineNumber)));
        return chunks;
    }

    private GoldSetCase parseGoldCase(JsonNode node, int lineNumber)
            throws GoldSetDataFormatException {
        requireObject(node, lineNumber);
        String queryId = requireIdentifier(node, "query_id", lineNumber);
        List<String> groundTruth = new ArrayList<>();
        JsonNode gt = node.get("ground_truth");
        if (gt != null && gt.isArray()) {
            for (JsonNode item : gt) {
                if (item != null && !item.isNull()) {
                    groundTruth.add(item.asText());
                }
            }
        }
        Map<String, Integer> relevance = new LinkedHashMap<>();
        JsonNode rel = node.get("relevance");
        if (rel != null && rel.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = rel.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                relevance.put(entry.getKey(), entry.getValue().asInt(0));
            }
        }
        return new GoldSetCase(
                queryId,
                node.path("question").asText(""),
                List.copyOf(groundTruth),
                Map.copyOf(relevance),
                node.path("source_article_id").asText(""),
                node.path("source_url").asText(""),
                node.path("category").asText("")
        );
    }

    private GoldSetChunk parseChunk(JsonNode node, int lineNumber)
            throws GoldSetDataFormatException {
        requireObject(node, lineNumber);
        String chunkId = requireIdentifier(node, "chunk_id", lineNumber);
        return new GoldSetChunk(
                chunkId,
                node.path("document_id").asText(""),
                node.path("chunk_index").asInt(0),
                node.path("content").asText(""),
                node.path("title").asText(""),
                node.path("category").asText(""),
                node.path("url").asText("")
        );
    }

    private void requireObject(JsonNode node, int lineNumber) throws GoldSetDataFormatException {
        if (node == null || !node.isObject()) {
            throw new GoldSetDataFormatException(lineNumber, "expected a JSON object");
        }
    }

    private String requireIdentifier(JsonNode node, String field, int lineNumber)
            throws GoldSetDataFormatException {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new GoldSetDataFormatException(
                    lineNumber,
                    "required field '" + field + "' must be non-blank"
            );
        }
        return value;
    }

    private void forEachJsonLine(Reader reader, LineConsumer consumer) throws IOException {
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            String line;
            int lineNumber = 0;
            while ((line = bufferedReader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(trimmed);
                } catch (IOException ex) {
                    throw new GoldSetDataFormatException(lineNumber, "invalid JSON", ex);
                }
                consumer.accept(node, lineNumber);
            }
        }
    }

    @FunctionalInterface
    private interface LineConsumer {
        void accept(JsonNode node, int lineNumber) throws IOException;
    }
}
