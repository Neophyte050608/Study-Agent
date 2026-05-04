package io.github.imzmq.interview.chat.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * LLM JSON 输出的 Schema 声明。
 * 用于 Layer 3 校验：必填字段、类型约束、枚举值约束。
 */
public final class SchemaSpec {

    public enum JsonType { STRING, NUMBER, BOOLEAN, ARRAY, OBJECT }

    private final Set<String> requiredFields;
    private final Map<String, JsonType> fieldTypes;
    private final Map<String, Object> fieldDefaults;
    private final Map<String, Set<String>> fieldAllowedValues;

    private SchemaSpec(Builder builder) {
        this.requiredFields = Collections.unmodifiableSet(new LinkedHashSet<>(builder.requiredFields));
        this.fieldTypes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fieldTypes));
        this.fieldDefaults = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fieldDefaults));
        this.fieldAllowedValues = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fieldAllowedValues));
    }

    public Set<String> requiredFields() { return requiredFields; }
    public Map<String, JsonType> fieldTypes() { return fieldTypes; }
    public Map<String, Object> fieldDefaults() { return fieldDefaults; }
    public Map<String, Set<String>> fieldAllowedValues() { return fieldAllowedValues; }
    public boolean isEmpty() { return requiredFields.isEmpty() && fieldTypes.isEmpty(); }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Set<String> requiredFields = new LinkedHashSet<>();
        private final Map<String, JsonType> fieldTypes = new LinkedHashMap<>();
        private final Map<String, Object> fieldDefaults = new LinkedHashMap<>();
        private final Map<String, Set<String>> fieldAllowedValues = new LinkedHashMap<>();

        public Builder required(String... fields) {
            for (String f : fields) requiredFields.add(f);
            return this;
        }

        public Builder type(String field, JsonType type, Object defaultValue) {
            fieldTypes.put(field, type);
            fieldDefaults.put(field, defaultValue);
            return this;
        }

        public Builder allowed(String field, String... values) {
            Set<String> set = new LinkedHashSet<>();
            for (String v : values) set.add(v);
            fieldAllowedValues.put(field, set);
            return this;
        }

        public SchemaSpec build() { return new SchemaSpec(this); }
    }
}
