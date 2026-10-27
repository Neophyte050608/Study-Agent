package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Executes one configured benchmark at startup; absent unless explicitly enabled. */
@Component
@ConditionalOnProperty(
        name = "app.knowledge.retrieval-benchmark.enabled",
        havingValue = "true"
)
public final class GoldSetBenchmarkRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GoldSetBenchmarkRunner.class);

    private final RetrievalBenchmarkProperties properties;
    private final GoldSetBenchmarkFileService fileService;

    public GoldSetBenchmarkRunner(
            RetrievalBenchmarkProperties properties,
            GoldSetBenchmarkFileService fileService
    ) {
        this.properties = properties;
        this.fileService = fileService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        GoldSetBenchmarkFileResult result = fileService.run(
                properties.requireGoldPath(),
                properties.requireCorpusPath(),
                properties.requireOutputPath()
        );
        log.info(
                "Retrieval benchmark completed: cases={}, chunks={}, candidateWins={}, baselineWins={}, ties={}, output={}",
                result.goldCaseCount(),
                result.corpusChunkCount(),
                result.report().candidateWins(),
                result.report().baselineWins(),
                result.report().ties(),
                result.outputFile()
        );
    }
}
