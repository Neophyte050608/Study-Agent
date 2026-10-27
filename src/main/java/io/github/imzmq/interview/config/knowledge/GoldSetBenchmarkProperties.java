package io.github.imzmq.interview.config.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** File locations for the explicitly enabled chunk-level retrieval benchmark runner. */
@Component
@ConfigurationProperties(prefix = "app.knowledge.retrieval-benchmark")
public class GoldSetBenchmarkProperties {

    private String goldFile = "";
    private String corpusFile = "";
    private String outputFile = "";

    public String getGoldFile() {
        return goldFile;
    }

    public void setGoldFile(String goldFile) {
        this.goldFile = normalize(goldFile);
    }

    public String getCorpusFile() {
        return corpusFile;
    }

    public void setCorpusFile(String corpusFile) {
        this.corpusFile = normalize(corpusFile);
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = normalize(outputFile);
    }

    public Path requireGoldPath() {
        return requirePath(goldFile, "gold-file");
    }

    public Path requireCorpusPath() {
        return requirePath(corpusFile, "corpus-file");
    }

    public Path requireOutputPath() {
        return requirePath(outputFile, "output-file");
    }

    private Path requirePath(String value, String property) {
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "app.knowledge.retrieval-benchmark." + property + " must be configured"
            );
        }
        return Path.of(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
