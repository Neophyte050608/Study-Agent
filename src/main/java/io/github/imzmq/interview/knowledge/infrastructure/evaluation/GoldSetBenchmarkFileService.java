package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetBenchmarkComparisonService;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetCase;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetChunk;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetComparisonMarkdownRenderer;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetComparisonReport;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetDataLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** File adapter that loads Saber-style JSONL and persists a Markdown comparison report. */
@Service
public final class GoldSetBenchmarkFileService {

    private final GoldSetDataLoader dataLoader = new GoldSetDataLoader();
    private final GoldSetBenchmarkComparisonService comparisonService;
    private final GoldSetComparisonMarkdownRenderer markdownRenderer;

    public GoldSetBenchmarkFileService(
            GoldSetBenchmarkComparisonService comparisonService,
            GoldSetComparisonMarkdownRenderer markdownRenderer
    ) {
        this.comparisonService = comparisonService;
        this.markdownRenderer = markdownRenderer;
    }

    public GoldSetBenchmarkFileResult run(
            Path goldFile,
            Path corpusFile,
            Path outputFile
    ) throws IOException {
        Path goldPath = requireReadableFile(goldFile, "gold file");
        Path corpusPath = requireReadableFile(corpusFile, "corpus file");
        Path outputPath = requireOutputPath(outputFile);

        List<GoldSetCase> cases;
        List<GoldSetChunk> chunks;
        try (Reader reader = Files.newBufferedReader(goldPath, StandardCharsets.UTF_8)) {
            cases = dataLoader.loadGoldCases(reader);
        }
        try (Reader reader = Files.newBufferedReader(corpusPath, StandardCharsets.UTF_8)) {
            chunks = dataLoader.loadChunks(reader);
        }

        GoldSetComparisonReport report = comparisonService.compare(cases, chunks);
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, markdownRenderer.render(report), StandardCharsets.UTF_8);
        return new GoldSetBenchmarkFileResult(cases.size(), chunks.size(), outputPath, report);
    }

    private Path requireReadableFile(Path path, String label) throws IOException {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException(label + " is not a readable regular file: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private Path requireOutputPath(Path path) throws IOException {
        if (path == null) {
            throw new IOException("output file must be configured");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            throw new IOException("output file points to a directory: " + normalized);
        }
        return normalized;
    }
}
