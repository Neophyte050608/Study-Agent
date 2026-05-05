package io.github.imzmq.interview.knowledge.application.evaluation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Package-private shared utilities for evaluation services.
 * Extracts duplicate helper methods from RetrievalEvaluationService and RAGQualityEvaluationService.
 */
final class EvaluationServiceHelper {

    private EvaluationServiceHelper() {
        // utility class, prevent instantiation
    }

    /**
     * Normalize a text value, returning the defaultValue when null or blank.
     */
    static String normalizeText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * Safely unpack a nullable Integer to int, defaulting to 0.
     */
    static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Safely unpack a nullable Double to double, defaulting to 0.0.
     */
    static double safeDouble(Double value) {
        return value == null ? 0.0D : value;
    }

    /**
     * Remove the ".json" suffix from a filename if present.
     */
    static String stripJsonSuffix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith(".json") ? value.substring(0, value.length() - 5) : value;
    }

    /**
     * Clamp a score to the range [0.0, 1.0].
     */
    static double clamp01(double score) {
        if (score < 0.0D) {
            return 0.0D;
        }
        if (score > 1.0D) {
            return 1.0D;
        }
        return score;
    }

    /**
     * Return an empty map when source is null, otherwise a defensive copy.
     */
    static Map<String, Object> safeMap(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    /**
     * Normalize a dataset filename: trim, ensure ".json" suffix, validate prefix.
     *
     * @param dataset        the raw dataset name or filename
     * @param defaultFile    fallback filename when input is null/blank
     * @param requiredPrefix the required filename prefix for validation
     * @return normalized filename
     * @throws IllegalArgumentException if the filename does not start with the required prefix
     */
    static String normalizeDatasetFilename(String dataset, String defaultFile, String requiredPrefix) {
        String candidate = dataset == null ? defaultFile : dataset.trim();
        if (candidate.isBlank()) {
            return defaultFile;
        }
        if (!candidate.endsWith(".json")) {
            candidate = candidate + ".json";
        }
        if (!candidate.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("不支持的数据集: " + dataset);
        }
        return candidate;
    }

    /**
     * Resolve a dataset alias or filename to a concrete filename.
     *
     * @param dataset        the dataset alias (e.g. "default", "baseline") or raw filename
     * @param defaultFile    fallback filename when input is null/blank
     * @param mapping        alias-to-filename mapping (e.g. {"default" -> "rag_ground_truth.json"})
     * @param requiredPrefix the required filename prefix for validation
     * @return resolved filename
     */
    static String resolveDatasetFilename(String dataset, String defaultFile, Map<String, String> mapping, String requiredPrefix) {
        if (dataset == null || dataset.isBlank()) {
            return defaultFile;
        }
        String normalized = dataset.trim().toLowerCase(Locale.ROOT);
        if (mapping.containsKey(normalized)) {
            return mapping.get(normalized);
        }
        return normalizeDatasetFilename(dataset, defaultFile, requiredPrefix);
    }
}
