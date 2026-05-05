package io.github.imzmq.interview.common;

/**
 * 通用字符串工具方法。
 */
public final class StringUtils {

    private StringUtils() {
        // 工具类不应实例化
    }

    /**
     * 将 null 转为空字符串，否则 trim。
     * 等价于 {@code value == null ? "" : value.trim()}。
     */
    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
