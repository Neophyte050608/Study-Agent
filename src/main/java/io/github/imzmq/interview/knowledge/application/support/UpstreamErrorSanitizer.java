package io.github.imzmq.interview.knowledge.application.support;

import java.util.regex.Pattern;

public final class UpstreamErrorSanitizer {

    private static final Pattern RAW_API_KEY_PATTERN = Pattern.compile("(?i)(\\\"?api[-_ ]?key\\\"?\\s*[:=]\\s*\\\"?)([^\\\",\\s]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._-]{8,})");
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9._-]{32,}\\b");

    private UpstreamErrorSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text.replaceAll("\\s+", " ").trim();
        sanitized = RAW_API_KEY_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = AUTHORIZATION_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = LONG_TOKEN_PATTERN.matcher(sanitized).replaceAll("***");
        return sanitized;
    }
}
