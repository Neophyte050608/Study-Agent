package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldSetDataLoaderTest {

    private final GoldSetDataLoader loader = new GoldSetDataLoader();

    @Test
    void parsesGoldCasesWithChunkIdAndGradedRelevance() throws Exception {
        String jsonl = "{\"query_id\":\"q001\",\"question\":\"如何调用远程 MCP？\","
                + "\"ground_truth\":[\"a_00000\",\"a_00012\",\"a_00024\"],"
                + "\"relevance\":{\"a_00000\":3,\"a_00012\":2,\"a_00024\":1},"
                + "\"source_article_id\":\"7663014213996609582\","
                + "\"source_url\":\"https://juejin.cn/post/7663014213996609582\","
                + "\"category\":\"人工智能\"}\n"
                + "{\"query_id\":\"q002\",\"question\":\"Preact Hydration 是什么？\","
                + "\"ground_truth\":[\"b_00000\"],\"relevance\":{\"b_00000\":3},"
                + "\"source_article_id\":\"7662777075909312563\","
                + "\"source_url\":\"https://juejin.cn/post/7662777075909312563\","
                + "\"category\":\"其他技术\"}\n";

        List<GoldSetCase> cases = loader.loadGoldCases(new StringReader(jsonl));

        assertThat(cases).hasSize(2);
        GoldSetCase first = cases.get(0);
        assertThat(first.queryId()).isEqualTo("q001");
        assertThat(first.question()).contains("远程 MCP");
        assertThat(first.groundTruth()).containsExactly("a_00000", "a_00012", "a_00024");
        assertThat(first.relevanceOf("a_00000")).isEqualTo(3);
        assertThat(first.relevanceOf("a_00024")).isEqualTo(1);
        assertThat(first.relevanceOf("missing")).isZero();
        assertThat(first.isRelevant("a_00012")).isTrue();
        assertThat(first.isRelevant("not_present")).isFalse();
        assertThat(first.sourceArticleId()).isEqualTo("7663014213996609582");
        assertThat(first.category()).isEqualTo("人工智能");
    }

    @Test
    void parsesCorpusChunksKeepingCoreFields() throws Exception {
        String jsonl = "{\"chunk_id\":\"a_00000\",\"document_id\":\"7652563235686350867\","
                + "\"chunk_index\":0,\"content\":\"正文内容\",\"title\":\"标题\","
                + "\"author\":\"忽略\",\"category\":\"人工智能\","
                + "\"url\":\"https://juejin.cn/post/7652563235686350867\","
                + "\"content_sha256\":\"deadbeef\"}\n";

        List<GoldSetChunk> chunks = loader.loadChunks(new StringReader(jsonl));

        assertThat(chunks).hasSize(1);
        GoldSetChunk chunk = chunks.get(0);
        assertThat(chunk.chunkId()).isEqualTo("a_00000");
        assertThat(chunk.documentId()).isEqualTo("7652563235686350867");
        assertThat(chunk.chunkIndex()).isZero();
        assertThat(chunk.content()).isEqualTo("正文内容");
        assertThat(chunk.title()).isEqualTo("标题");
        assertThat(chunk.category()).isEqualTo("人工智能");
        assertThat(chunk.url()).contains("7652563235686350867");
    }

    @Test
    void ignoresUnknownFieldsInGenericCorpusRows() throws Exception {
        List<GoldSetChunk> chunks = loader.loadChunks(new StringReader(
                "{\"chunk_id\":\"c1\",\"content\":\"Redis\",\"vendor_field\":42}\n"));

        assertThat(chunks).singleElement()
                .extracting(GoldSetChunk::chunkId)
                .isEqualTo("c1");
    }

    @Test
    void skipsBlankLinesButRejectsMalformedJsonWithPhysicalLineNumber() {
        String jsonl = "\n"
                + "{\"query_id\":\"q001\",\"question\":\"Q\",\"ground_truth\":[\"a\"]}\n"
                + "this-is-not-json\n";

        assertThatThrownBy(() -> loader.loadGoldCases(new StringReader(jsonl)))
                .isInstanceOf(GoldSetDataFormatException.class)
                .hasMessageContaining("line 3")
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void rejectsMissingOrBlankRequiredIdentifiersWithPhysicalLineNumber() {
        String goldJsonl = "\n{\"question\":\"no id\",\"ground_truth\":[\"a\"]}\n";
        String chunkJsonl = "{\"chunk_id\":\"   \",\"document_id\":\"doc\"}\n";

        assertThatThrownBy(() -> loader.loadGoldCases(new StringReader(goldJsonl)))
                .isInstanceOf(GoldSetDataFormatException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("query_id");
        assertThatThrownBy(() -> loader.loadChunks(new StringReader(chunkJsonl)))
                .isInstanceOf(GoldSetDataFormatException.class)
                .hasMessageContaining("line 1")
                .hasMessageContaining("chunk_id");
    }

}
