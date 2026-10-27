package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Executes exactly one configured benchmark operation and is absent unless explicitly enabled. */
@Component
@ConditionalOnProperty(
        name = "app.knowledge.retrieval-benchmark.enabled",
        havingValue = "true"
)
public final class RetrievalBenchmarkRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalBenchmarkRunner.class);

    private final RetrievalBenchmarkProperties properties;
    private final RetrievalBenchmarkFileService fileService;

    public RetrievalBenchmarkRunner(
            RetrievalBenchmarkProperties properties,
            RetrievalBenchmarkFileService fileService
    ) {
        this.properties = properties;
        this.fileService = fileService;
    }

    @Override
    public void run(ApplicationArguments args) {
        RetrievalBenchmarkFileResult result = fileService.execute(properties.getOperation());
        log.info(
                "Retrieval benchmark operation completed: operation={}, cases={}, chunks={}, output={}",
                result.operation(),
                result.goldCaseCount(),
                result.corpusChunkCount(),
                result.outputFile());
    }
}
