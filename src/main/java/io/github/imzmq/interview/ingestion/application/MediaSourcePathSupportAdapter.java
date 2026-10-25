package io.github.imzmq.interview.ingestion.application;

import io.github.imzmq.interview.ingestion.application.contract.IngestionConfigOperations;
import io.github.imzmq.interview.media.application.contract.extension.MediaSourcePathSupport;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MediaSourcePathSupportAdapter implements MediaSourcePathSupport {

    private final IngestionConfigOperations ingestionConfigOperations;

    public MediaSourcePathSupportAdapter(IngestionConfigOperations ingestionConfigOperations) {
        this.ingestionConfigOperations = ingestionConfigOperations;
    }

    @Override
    public MediaSourcePaths currentSourcePaths() {
        Map<String, String> config = ingestionConfigOperations.getConfig();
        return new MediaSourcePaths(config.get("paths"), config.get("imagePath"));
    }
}
