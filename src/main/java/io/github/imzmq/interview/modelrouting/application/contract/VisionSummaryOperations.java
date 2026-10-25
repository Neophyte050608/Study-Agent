package io.github.imzmq.interview.modelrouting.application.contract;

import java.nio.file.Path;

/** Public vision summarization capability with built-in provider fallback. */
public interface VisionSummaryOperations {

    String summarize(Path imagePath, String fallbackName, String sectionPath, String nearbyContext);
}
