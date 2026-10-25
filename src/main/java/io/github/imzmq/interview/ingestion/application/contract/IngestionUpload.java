package io.github.imzmq.interview.ingestion.application.contract;

import java.io.IOException;
import java.io.InputStream;

/** Framework-neutral, lazily readable upload supplied to the ingestion module. */
public interface IngestionUpload {

    String originalFilename();

    byte[] bytes() throws IOException;

    InputStream inputStream() throws IOException;
}
