package com.example.interview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.entity.IngestConfigDO;
import com.example.interview.mapper.IngestConfigMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 知识入库配置服务。
 * 负责管理 /api/ingest/config 的配置读取与持久化。
 */
@Service
public class IngestConfigService {

    private static final String DEFAULT_CONFIG_KEY = "default";

    private final IngestConfigMapper ingestConfigMapper;
    private final String defaultPaths;
    private final String defaultImagePath;
    private final String defaultIgnoreDirs;

    public IngestConfigService(
            IngestConfigMapper ingestConfigMapper,
            @Value("${app.ingestion.config.paths:}") String defaultPaths,
            @Value("${app.ingestion.config.image-path:}") String defaultImagePath,
            @Value("${app.ingestion.config.ignore-dirs:}") String defaultIgnoreDirs
    ) {
        this.ingestConfigMapper = ingestConfigMapper;
        this.defaultPaths = defaultPaths == null ? "" : defaultPaths;
        this.defaultImagePath = defaultImagePath == null ? "" : defaultImagePath;
        this.defaultIgnoreDirs = defaultIgnoreDirs == null ? "" : defaultIgnoreDirs;
    }

    /**
     * 获取当前入库配置。
     * 当数据库未配置时，回退到 application.yml 默认值。
     */
    public Map<String, String> getConfig() {
        IngestConfigDO configDO = ingestConfigMapper.selectOne(
                Wrappers.<IngestConfigDO>lambdaQuery().eq(IngestConfigDO::getConfigKey, DEFAULT_CONFIG_KEY)
        );
        if (configDO == null) {
            return Map.of(
                    "paths", defaultPaths,
                    "imagePath", defaultImagePath,
                    "ignoreDirs", defaultIgnoreDirs
            );
        }
        return Map.of(
                "paths", normalize(configDO.getPaths()),
                "imagePath", normalize(configDO.getImagePath()),
                "ignoreDirs", normalize(configDO.getIgnoreDirs())
        );
    }

    /**
     * 保存入库配置。
     * 同一配置键采用 upsert 方式写入，避免产生多条历史冗余记录。
     */
    public Map<String, String> saveConfig(Map<String, String> payload) {
        String paths = payload == null ? "" : normalize(payload.get("paths"));
        String imagePath = payload == null ? "" : normalize(payload.get("imagePath"));
        String ignoreDirs = payload == null ? "" : normalize(payload.get("ignoreDirs"));
        IngestConfigDO existing = ingestConfigMapper.selectOne(
                Wrappers.<IngestConfigDO>lambdaQuery().eq(IngestConfigDO::getConfigKey, DEFAULT_CONFIG_KEY)
        );
        if (existing == null) {
            IngestConfigDO created = new IngestConfigDO();
            created.setConfigKey(DEFAULT_CONFIG_KEY);
            created.setPaths(paths);
            created.setImagePath(imagePath);
            created.setIgnoreDirs(ignoreDirs);
            ingestConfigMapper.insert(created);
        } else {
            existing.setPaths(paths);
            existing.setImagePath(imagePath);
            existing.setIgnoreDirs(ignoreDirs);
            ingestConfigMapper.updateById(existing);
        }
        return Map.of(
                "paths", paths,
                "imagePath", imagePath,
                "ignoreDirs", ignoreDirs
        );
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
