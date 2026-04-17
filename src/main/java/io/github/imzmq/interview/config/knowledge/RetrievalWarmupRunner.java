package io.github.imzmq.interview.config.knowledge;

import io.github.imzmq.interview.graph.domain.TechConceptRepository;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时对检索相关后端执行预热查询，避免首次请求冷启动延迟。
 */
@Component
@ConditionalOnProperty(name = "app.knowledge.retrieval.warmup-enabled", havingValue = "true", matchIfMissing = true)
public class RetrievalWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalWarmupRunner.class);

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<LexicalIndexService> lexicalIndexServiceProvider;
    private final ObjectProvider<TechConceptRepository> techConceptRepositoryProvider;

    public RetrievalWarmupRunner(ObjectProvider<VectorStore> vectorStoreProvider,
                                 ObjectProvider<LexicalIndexService> lexicalIndexServiceProvider,
                                 ObjectProvider<TechConceptRepository> techConceptRepositoryProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.lexicalIndexServiceProvider = lexicalIndexServiceProvider;
        this.techConceptRepositoryProvider = techConceptRepositoryProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始检索后端预热...");
        long start = System.currentTimeMillis();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        LexicalIndexService lexicalIndexService = lexicalIndexServiceProvider.getIfAvailable();
        TechConceptRepository techConceptRepository = techConceptRepositoryProvider.getIfAvailable();

        if (vectorStore == null) {
            log.info("跳过 Milvus 预热：VectorStore Bean 不存在");
        } else {
            try {
                vectorStore.similaritySearch(SearchRequest.builder().query("warmup").topK(1).build());
                log.info("Milvus 预热完成");
            } catch (Exception e) {
                log.warn("Milvus 预热失败（不影响正常使用）: {}", e.getMessage());
            }
        }

        if (lexicalIndexService == null) {
            log.info("跳过 MySQL 全文索引预热：LexicalIndexService Bean 不存在");
        } else {
            try {
                lexicalIndexService.searchIntentDirected("warmup", List.of("warmup"), 1);
                log.info("MySQL 全文索引预热完成");
            } catch (Exception e) {
                log.warn("MySQL 全文索引预热失败（不影响正常使用）: {}", e.getMessage());
            }
        }

        if (techConceptRepository == null) {
            log.info("跳过 Neo4j 图谱预热：TechConceptRepository Bean 不存在");
        } else {
            try {
                techConceptRepository.findRelatedConceptSnippetsWithinTwoHops("warmup");
                log.info("Neo4j 图谱预热完成");
            } catch (Exception e) {
                log.warn("Neo4j 图谱预热失败（不影响正常使用）: {}", e.getMessage());
            }
        }

        log.info("检索后端预热完成，耗时 {}ms", System.currentTimeMillis() - start);
    }
}



