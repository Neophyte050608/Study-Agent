package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.RagChildDO;
import com.example.interview.entity.RagParentDO;
import com.example.interview.mapper.RagChildMapper;
import com.example.interview.mapper.RagParentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ParentChildIndexService {

    private static final Logger logger = LoggerFactory.getLogger(ParentChildIndexService.class);

    private final RagParentMapper ragParentMapper;
    private final RagChildMapper ragChildMapper;

    public ParentChildIndexService(RagParentMapper ragParentMapper, RagChildMapper ragChildMapper) {
        this.ragParentMapper = ragParentMapper;
        this.ragChildMapper = ragChildMapper;
    }

    public void rebuildByChunks(String filePath, List<Document> chunks) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        deleteByFilePath(filePath);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Map<String, RagParentDO> parentMap = new LinkedHashMap<>();
        List<RagChildDO> children = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Document chunk : chunks) {
            if (chunk == null || chunk.getMetadata() == null) {
                continue;
            }
            String parentId = asString(chunk.getMetadata().get("parent_id"));
            if (parentId == null || parentId.isBlank()) {
                continue;
            }
            String parentText = asString(chunk.getMetadata().get("parent_text"));
            String sectionPath = asString(chunk.getMetadata().get("section_path"));
            String sourceType = asString(chunk.getMetadata().get("source_type"));
            String knowledgeTags = asString(chunk.getMetadata().get("knowledge_tags"));
            parentMap.computeIfAbsent(parentId, key -> {
                RagParentDO parentDO = new RagParentDO();
                parentDO.setParentId(parentId);
                parentDO.setFilePath(filePath);
                parentDO.setSectionPath(sectionPath);
                parentDO.setSourceType(sourceType);
                parentDO.setKnowledgeTags(knowledgeTags);
                parentDO.setParentText(parentText == null ? "" : parentText);
                parentDO.setParentHash(DigestUtils.md5DigestAsHex((parentText == null ? "" : parentText).getBytes(StandardCharsets.UTF_8)));
                parentDO.setCreatedAt(now);
                parentDO.setUpdatedAt(now);
                return parentDO;
            });
            RagChildDO childDO = new RagChildDO();
            String childId = asString(chunk.getMetadata().get("child_id"));
            childDO.setChildId(childId == null || childId.isBlank() ? chunk.getId() : childId);
            childDO.setParentId(parentId);
            childDO.setChildIndex(toInteger(chunk.getMetadata().get("child_index")));
            childDO.setChildText(chunk.getText());
            childDO.setChunkStrategy(asString(chunk.getMetadata().get("chunk_strategy")));
            childDO.setVectorDocId(chunk.getId());
            childDO.setCreatedAt(now);
            childDO.setUpdatedAt(now);
            children.add(childDO);
        }
        for (RagParentDO parentDO : parentMap.values()) {
            ragParentMapper.insert(parentDO);
        }
        for (RagChildDO child : children) {
            ragChildMapper.insert(child);
        }
        logger.info("ParentChild 索引重建完成: filePath={}, parents={}, children={}", filePath, parentMap.size(), children.size());
    }

    public void deleteByFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        List<RagParentDO> parents = ragParentMapper.selectList(new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getFilePath, filePath));
        if (!parents.isEmpty()) {
            Set<String> parentIds = parents.stream().map(RagParentDO::getParentId).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
            if (!parentIds.isEmpty()) {
                ragChildMapper.delete(new LambdaQueryWrapper<RagChildDO>().in(RagChildDO::getParentId, parentIds));
            }
            ragParentMapper.delete(new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getFilePath, filePath));
        }
    }

    public Map<String, RagParentDO> queryParentsByIds(Set<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        List<RagParentDO> parentRows = ragParentMapper.selectList(new LambdaQueryWrapper<RagParentDO>().in(RagParentDO::getParentId, parentIds));
        Map<String, RagParentDO> result = new LinkedHashMap<>();
        for (RagParentDO row : parentRows) {
            result.put(row.getParentId(), row);
        }
        return result;
    }

    public long countParents() {
        return ragParentMapper.selectCount(null);
    }

    public long countChildren() {
        return ragChildMapper.selectCount(null);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
