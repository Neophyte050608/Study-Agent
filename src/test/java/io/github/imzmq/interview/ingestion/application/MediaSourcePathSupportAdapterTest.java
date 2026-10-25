package io.github.imzmq.interview.ingestion.application;

import io.github.imzmq.interview.ingestion.application.contract.IngestionConfigOperations;
import io.github.imzmq.interview.media.application.contract.extension.MediaSourcePathSupport.MediaSourcePaths;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaSourcePathSupportAdapterTest {

    @Test
    void mapsIngestionConfigurationToMediaOwnedSourcePaths() {
        IngestionConfigOperations configOperations = mock(IngestionConfigOperations.class);
        when(configOperations.getConfig()).thenReturn(Map.of(
                "paths", "/vault-a\n/vault-b",
                "imagePath", "/images",
                "ignoreDirs", ".git"
        ));
        MediaSourcePathSupportAdapter adapter = new MediaSourcePathSupportAdapter(configOperations);

        MediaSourcePaths sourcePaths = adapter.currentSourcePaths();

        assertThat(sourcePaths).isEqualTo(new MediaSourcePaths("/vault-a\n/vault-b", "/images"));
        verify(configOperations).getConfig();
    }

    @Test
    void preservesMissingConfigurationValuesAsNull() {
        IngestionConfigOperations configOperations = mock(IngestionConfigOperations.class);
        when(configOperations.getConfig()).thenReturn(Map.of());
        MediaSourcePathSupportAdapter adapter = new MediaSourcePathSupportAdapter(configOperations);

        assertThat(adapter.currentSourcePaths()).isEqualTo(new MediaSourcePaths(null, null));
    }
}
