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
 * 作为 chunk 级检索评测的数据入口。解析具备容错性：空行跳过，单行解析失败时跳过该行，
 * 避免个别脏数据导致整份数据集不可用。</p>
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
        forEachJsonLine(reader, node -> {
            GoldSetCase parsed = parseGoldCase(node);
            if (parsed != null) {
                cases.add(parsed);
            }
        });
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
        forEachJsonLine(reader, node -> {
            GoldSetChunk parsed = parseChunk(node);
            if (parsed != null) {
                chunks.add(parsed);
            }
        });
        return chunks;
    }

    private GoldSetCase parseGoldCase(JsonNode node) {
        if (node == null || !node.hasNonNull("query_id")) {
            return null;
        }
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
                node.path("query_id").asText(),
                node.path("question").asText(""),
                List.copyOf(groundTruth),
                Map.copyOf(relevance),
                node.path("source_article_id").asText(""),
                node.path("source_url").asText(""),
                node.path("category").asText("")
        );
    }

    private GoldSetChunk parseChunk(JsonNode node) {
        if (node == null || !node.hasNonNull("chunk_id")) {
            return null;
        }
        return new GoldSetChunk(
                node.path("chunk_id").asText(),
                node.path("document_id").asText(""),
                node.path("chunk_index").asInt(0),
                node.path("content").asText(""),
                node.path("title").asText(""),
                node.path("category").asText(""),
                node.path("url").asText("")
        );
    }

    private void forEachJsonLine(Reader reader, LineConsumer consumer) throws IOException {
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(trimmed);
                } catch (IOException ex) {
                    // 跳过单行脏数据，保证整份数据集仍可解析。
                    continue;
                }
                consumer.accept(node);
            }
        }
    }

    @FunctionalInterface
    private interface LineConsumer {
        void accept(JsonNode node);
    }
}
