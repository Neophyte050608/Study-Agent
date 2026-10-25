package io.github.imzmq.interview.media.application.contract.extension;

/**
 * Media-owned source-path extension implemented by the module that owns ingestion configuration.
 */
public interface MediaSourcePathSupport {

    MediaSourcePaths currentSourcePaths();

    record MediaSourcePaths(String vaultPaths, String imagePath) {
    }
}
