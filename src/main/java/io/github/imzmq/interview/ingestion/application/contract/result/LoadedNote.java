package io.github.imzmq.interview.ingestion.application.contract.result;

import java.nio.file.Path;

/** Framework-neutral immutable view of a note found on the local filesystem. */
public record LoadedNote(Path path) {
    public LoadedNote {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        path = path.toAbsolutePath().normalize();
    }
}
