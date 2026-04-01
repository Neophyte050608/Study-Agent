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

/**
 * Parent-Child 索引服务。
 *
 * <p>该服务用于将“切块后的子片段（child）”与“较大语义段落（parent）”建立稳定映射，并持久化到数据库：</p>
 * <ul>
 *     <li>入库阶段：根据 {@link org.springframework.ai.document.Document} 的 metadata 写入 parent/child 两张表</li>
 *     <li>检索阶段：根据召回的 child 反查 parent 文本，用于“父文上下文 + 命中片段”回填与证据构建</li>
 * </ul>
 *
 * <p>元数据约定（由切分链路写入）：</p>
 * <ul>
 *     <li>{@code parent_id}：父文唯一标识（同一文件内按章节/段落生成）</li>
 *     <li>{@code parent_text}：父文原始文本（用于回填上下文窗口）</li>
 *     <li>{@code child_id}：子块唯一标识（可选；缺省回退 Document.id）</li>
 *     <li>{@code child_index}：子块在父文中的序号（可选；用于排序/展示）</li>
 *     <li>{@code file_path/section_path/source_type/knowledge_tags}：用于观测与过滤的辅助字段</li>
 * </ul>
 *
 * <p>一致性策略：</p>
 * <ul>
 *     <li>以 filePath 为粒度“先删后写”重建索引，确保单文件重建的幂等性</li>
 *     <li>删除时先查 parentId，再批量删除 child，避免残留孤儿 child 记录</li>
 * </ul>
 */
@Service
public class ParentChildIndexService {

    private static final Logger logger = LoggerFactory.getLogger(ParentChildIndexService.class);

    private final RagParentMapper ragParentMapper;
    private final RagChildMapper ragChildMapper;

    public ParentChildIndexService(RagParentMapper ragParentMapper, RagChildMapper ragChildMapper) {
        this.ragParentMapper = ragParentMapper;
        this.ragChildMapper = ragChildMapper;
    }

    /**
     * 按文件维度重建 Parent-Child 索引。
     *
     * <p>该方法会：</p>
     * <ul>
     *     <li>先删除该 filePath 相关的 parent/child 记录</li>
     *     <li>从 chunks 的 metadata 中抽取 parent/child 信息并落库</li>
     * </ul>
     *
     * <p>注意：chunks 为空时只会执行删除，等价于“该文件已无可入库内容”。</p>
     *
     * @param filePath 文件路径（作为 parent 记录的归属键）
     * @param chunks   切块后的子文档列表（每个 Document 需携带 parent_id 等元数据）
     */
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
            // parent_id 是建立父子映射的核心键；缺失则无法回填父文上下文，直接跳过该 chunk。
            String parentId = asString(chunk.getMetadata().get("parent_id"));
            if (parentId == null || parentId.isBlank()) {
                continue;
            }
            // parent_text 为父文原始文本；后续检索回填会基于命中片段裁剪出上下文窗口。
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
            // child_id 允许缺省：若切分器未提供，则回退使用向量文档 id，保持可追溯性。
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

    /**
     * 删除指定文件路径下的所有 parent/child 索引记录。
     *
     * <p>删除顺序：</p>
     * <ol>
     *     <li>先查出该 filePath 下所有 parentId</li>
     *     <li>按 parentId 批量删除 child</li>
     *     <li>再删除 parent</li>
     * </ol>
     *
     * @param filePath 文件路径
     */
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

    /**
     * 批量查询 parent 记录，并按 parentId 映射返回。
     *
     * <p>该方法主要用于检索阶段：先召回 child，再按 parentId 批量回填父文文本，
     * 避免逐条查询导致的 N+1 问题。</p>
     *
     * @param parentIds parentId 集合
     * @return parentId -> parentRow 的映射；入参为空则返回空 Map
     */
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

    /**
     * @return parent 表记录数（用于观测/报表）
     */
    public long countParents() {
        return ragParentMapper.selectCount(null);
    }

    /**
     * @return child 表记录数（用于观测/报表）
     */
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
