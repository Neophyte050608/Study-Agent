package io.github.imzmq.interview.chat.application.contract;

import java.util.List;
import java.util.Map;

/**
 * Loads prompt-owned few-shot examples without exposing Chat persistence details.
 */
public interface PromptExampleQuery {

    List<Map<String, Object>> loadFewShotCases(String path);
}
