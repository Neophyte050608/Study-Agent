package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Writes a complete report to a temporary file before replacing the visible result. */
public final class AtomicBenchmarkReportWriter {

    public Path write(Path target, String content) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        Path normalized = target.toAbsolutePath().normalize();
        Path temporary = normalized.resolveSibling(normalized.getFileName() + ".tmp");
        try {
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            moveIntoPlace(temporary, normalized);
            return normalized;
        } catch (IOException | RuntimeException exception) {
            throw new BenchmarkOperationException("BENCHMARK_REPORT_WRITE_FAILED", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A completed target is authoritative; stale temporary files are safe to retry later.
            }
        }
    }

    private void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
