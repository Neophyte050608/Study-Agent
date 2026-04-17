package io.github.imzmq.interview.knowledge.application.indexing;

import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地知识范围配置读取服务。
 */
@Service
public class LocalKnowledgeScopeService {

    public LocalKnowledgeScope load(Path scopePath) {
        if (scopePath == null) {
            throw new IllegalArgumentException("scopePath 不能为空");
        }
        Path normalized = scopePath.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Scope 配置文件不存在: " + normalized);
        }
        try (InputStream inputStream = Files.newInputStream(normalized)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(inputStream);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Scope 配置格式无效: 顶层必须是对象");
            }
            String vaultRoot = stringValue(map.get("vault_root"));
            if (vaultRoot.isBlank()) {
                throw new IllegalArgumentException("Scope 配置缺少 vault_root");
            }
            return new LocalKnowledgeScope(
                    normalized.toString(),
                    vaultRoot,
                    stringList(map.get("include_dirs")),
                    stringList(map.get("exclude_dirs")),
                    stringList(map.get("review_dirs")),
                    stringList(map.get("default_ignore_dir_names")),
                    nestedMap(map.get("note_rules")),
                    nestedMap(map.get("index_rules"))
            );
        } catch (IOException e) {
            throw new IllegalStateException("读取 Scope 配置失败: " + normalized, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String normalized = stringValue(item);
            if (!normalized.isBlank()) {
                result.add(normalized.replace("\\", "/"));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> nestedMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey());
            if (!key.isBlank()) {
                result.put(key, entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    public record LocalKnowledgeScope(String scopePath,
                                      String vaultRoot,
                                      List<String> includeDirs,
                                      List<String> excludeDirs,
                                      List<String> reviewDirs,
                                      List<String> defaultIgnoreDirNames,
                                      Map<String, Object> noteRules,
                                      Map<String, Object> indexRules) {
    }
}




