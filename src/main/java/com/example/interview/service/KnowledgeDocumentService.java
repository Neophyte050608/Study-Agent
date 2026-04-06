package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.KnowledgeChunkCtrlDO;
import com.example.interview.entity.KnowledgeDocumentDO;
import com.example.interview.entity.RagChildDO;
import com.example.interview.entity.RagParentDO;
import com.example.interview.ingestion.IngestionTaskService;
import com.example.interview.mapper.KnowledgeChunkCtrlMapper;
import com.example.interview.mapper.KnowledgeDocumentMapper;
import com.example.interview.mapper.RagChildMapper;
import com.example.interview.mapper.RagParentMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeDocumentService {

    private final RagParentMapper ragParentMapper;
    private final RagChildMapper ragChildMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkCtrlMapper knowledgeChunkCtrlMapper;
    private final IngestionService ingestionService;
    private final IngestionTaskService ingestionTaskService;

    public KnowledgeDocumentService(
            RagParentMapper ragParentMapper,
            RagChildMapper ragChildMapper,
            KnowledgeDocumentMapper knowledgeDocumentMapper,
            KnowledgeChunkCtrlMapper knowledgeChunkCtrlMapper,
            IngestionService ingestionService,
            IngestionTaskService ingestionTaskService
    ) {
        this.ragParentMapper = ragParentMapper;
        this.ragChildMapper = ragChildMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkCtrlMapper = knowledgeChunkCtrlMapper;
        this.ingestionService = ingestionService;
        this.ingestionTaskService = ingestionTaskService;
    }

    public Map<String, Object> pageDocuments(Long kbId, Integer pageNo, Integer pageSize, String status, String keyword) {
        int p = pageNo == null || pageNo <= 0 ? 1 : pageNo;
        int s = pageSize == null || pageSize <= 0 ? 10 : Math.min(pageSize, 100);
        Map<String, List<RagParentDO>> parentsBySource = ragParentMapper.selectList(new LambdaQueryWrapper<RagParentDO>())
                .stream()
                .filter(item -> item.getFilePath() != null && !item.getFilePath().isBlank())
                .collect(Collectors.groupingBy(RagParentDO::getFilePath, LinkedHashMap::new, Collectors.toList()));
        Map<String, KnowledgeDocumentDO> docRowsBySource = knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentDO>())
                .stream()
                .filter(item -> item.getSourceLocation() != null && !item.getSourceLocation().isBlank())
                .collect(Collectors.toMap(
                        KnowledgeDocumentDO::getSourceLocation,
                        item -> item,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));

        Set<String> sourceLocations = new LinkedHashSet<>();
        sourceLocations.addAll(docRowsBySource.keySet());
        sourceLocations.addAll(parentsBySource.keySet());

        List<Map<String, Object>> allDocuments = sourceLocations.stream()
                .map(sourceLocation -> buildDocumentItem(
                        kbId == null ? 1L : kbId,
                        sourceLocation,
                        parentsBySource.getOrDefault(sourceLocation, List.of()),
                        docRowsBySource.get(sourceLocation)
                ))
                .filter(Objects::nonNull)
                .filter(item -> matchesDocumentFilters(item, status, keyword))
                .sorted(Comparator
                        .comparing((Map<String, Object> item) -> (LocalDateTime) item.get("_sortTime"),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(item -> String.valueOf(item.getOrDefault("sourceLocation", "")), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        int total = allDocuments.size();
        int from = Math.max(0, (p - 1) * s);
        int to = Math.min(total, from + s);
        List<Map<String, Object>> records = from >= to ? List.of() : allDocuments.subList(from, to)
                .stream()
                .peek(item -> item.remove("_sortTime"))
                .peek(item -> item.remove("_searchBlob"))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", p);
        result.put("size", s);
        result.put("total", total);
        result.put("records", records);
        return result;
    }

    public Optional<Map<String, Object>> getDocumentDetail(String docId) {
        DocumentScope scope = resolveDocumentScope(docId);
        if (scope == null) {
            return Optional.empty();
        }
        KnowledgeDocumentDO docRow = scope.sourceLocation == null ? null : knowledgeDocumentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentDO>()
                        .eq(KnowledgeDocumentDO::getSourceLocation, scope.sourceLocation)
                        .last("LIMIT 1")
        );
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docId", scope.canonicalDocId);
        item.put("docName", resolveDocName(docRow, scope.sourceLocation));
        item.put("sourceType", Optional.ofNullable(docRow)
                .map(KnowledgeDocumentDO::getSourceType)
                .filter(text -> !text.isBlank())
                .orElseGet(() -> scope.parents.stream()
                        .map(RagParentDO::getSourceType)
                        .filter(Objects::nonNull)
                        .filter(text -> !text.isBlank())
                        .findFirst()
                        .orElse("LOCAL_VAULT")));
        item.put("sourceLocation", scope.sourceLocation);
        item.put("knowledgeTags", scope.parents.stream()
                .map(RagParentDO::getKnowledgeTags)
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .distinct()
                .collect(Collectors.joining(", ")));
        return Optional.of(item);
    }

    public Map<String, Object> pageChunks(String docId, Integer current, Integer size, Boolean enabled) {
        if (enabled != null && !enabled) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("current", 1);
            empty.put("size", size == null || size <= 0 ? 10 : Math.min(size, 100));
            empty.put("total", 0);
            empty.put("records", List.of());
            return empty;
        }
        int p = current == null || current <= 0 ? 1 : current;
        int s = size == null || size <= 0 ? 10 : Math.min(size, 100);
        DocumentScope scope = resolveDocumentScope(docId);
        if (scope == null || scope.parentIds.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("current", p);
            empty.put("size", s);
            empty.put("total", 0);
            empty.put("records", List.of());
            return empty;
        }
        Map<String, RagParentDO> parentMap = scope.parents.stream().collect(Collectors.toMap(
                RagParentDO::getParentId,
                item -> item,
                (left, right) -> left,
                LinkedHashMap::new
        ));
        List<RagChildDO> all = ragChildMapper.selectList(new LambdaQueryWrapper<RagChildDO>().in(RagChildDO::getParentId, scope.parentIds))
                .stream()
                .sorted(Comparator
                        .comparing((RagChildDO item) -> {
                            RagParentDO parent = parentMap.get(item.getParentId());
                            return parent == null ? "" : Optional.ofNullable(parent.getSectionPath()).orElse("");
                        }, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(item -> Optional.ofNullable(item.getChildIndex()).orElse(Integer.MAX_VALUE))
                        .thenComparing(item -> Optional.ofNullable(item.getChildId()).orElse("")))
                .collect(Collectors.toList());
        int total = all.size();
        int from = Math.max(0, (p - 1) * s);
        int to = Math.min(total, from + s);
        List<RagChildDO> pageItems = from >= to ? List.of() : all.subList(from, to);
        List<Map<String, Object>> records = new ArrayList<>();
        for (RagChildDO child : pageItems) {
            KnowledgeChunkCtrlDO ctrl = knowledgeChunkCtrlMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeChunkCtrlDO>()
                            .eq(KnowledgeChunkCtrlDO::getDocId, scope.canonicalDocId)
                            .eq(KnowledgeChunkCtrlDO::getChunkId, child.getChildId())
                            .last("LIMIT 1")
            );
            RagParentDO parent = parentMap.get(child.getParentId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", child.getChildId());
            item.put("childIndex", child.getChildIndex());
            item.put("enabled", ctrl == null || Boolean.TRUE.equals(ctrl.getEnabled()));
            item.put("snippet", Optional.ofNullable(child.getChildText()).map(txt -> txt.length() > 200 ? txt.substring(0, 200) + "..." : txt).orElse(""));
            item.put("sectionPath", parent == null ? "" : Optional.ofNullable(parent.getSectionPath()).orElse(""));
            records.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", p);
        result.put("size", s);
        result.put("total", total);
        result.put("records", records);
        return result;
    }

    public boolean setDocumentEnabled(String docId, boolean enabled) {
        DocumentScope scope = resolveDocumentScope(docId);
        if (scope == null || scope.sourceLocation == null || scope.sourceLocation.isBlank()) {
            return false;
        }
        KnowledgeDocumentDO existing = knowledgeDocumentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentDO>().eq(KnowledgeDocumentDO::getSourceLocation, scope.sourceLocation).last("LIMIT 1")
        );
        if (existing == null) {
            KnowledgeDocumentDO row = new KnowledgeDocumentDO();
            row.setKbId(1L);
            row.setDocName(resolveDocName(null, scope.sourceLocation));
            row.setStatus("READY");
            row.setEnabled(enabled);
            row.setSourceType(scope.parents.stream()
                    .map(RagParentDO::getSourceType)
                    .filter(Objects::nonNull)
                    .filter(text -> !text.isBlank())
                    .findFirst()
                    .orElse("LOCAL_VAULT"));
            row.setSourceLocation(scope.sourceLocation);
            row.setChunkCount(countChunks(scope.parentIds));
            knowledgeDocumentMapper.insert(row);
        } else {
            existing.setEnabled(enabled);
            knowledgeDocumentMapper.updateById(existing);
        }
        return true;
    }

    public boolean deleteDocument(String docId) {
        if (ingestionTaskService.hasRunningTasks()) {
            return false;
        }
        DocumentScope scope = resolveDocumentScope(docId);
        if (scope == null || scope.sourceLocation == null || scope.sourceLocation.isBlank()) {
            return false;
        }
        boolean ok = ingestionService.deleteByFilePath(scope.sourceLocation);
        knowledgeDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentDO>().eq(KnowledgeDocumentDO::getSourceLocation, scope.sourceLocation));
        return ok;
    }

    public boolean rechunkDocument(String docId) {
        if (ingestionTaskService.hasRunningTasks()) {
            return false;
        }
        DocumentScope scope = resolveDocumentScope(docId);
        if (scope == null || scope.sourceLocation == null || scope.sourceLocation.isBlank()) {
            return false;
        }
        return ingestionService.rechunkByFilePath(scope.sourceLocation);
    }

    public boolean setChunkEnabled(String docId, String chunkId, boolean enabled) {
        DocumentScope scope = resolveDocumentScope(docId);
        if (scope == null || scope.canonicalDocId == null || scope.canonicalDocId.isBlank()) {
            return false;
        }
        KnowledgeChunkCtrlDO existing = knowledgeChunkCtrlMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeChunkCtrlDO>()
                        .eq(KnowledgeChunkCtrlDO::getDocId, scope.canonicalDocId)
                        .eq(KnowledgeChunkCtrlDO::getChunkId, chunkId)
                        .last("LIMIT 1")
        );
        if (existing == null) {
            KnowledgeChunkCtrlDO row = new KnowledgeChunkCtrlDO();
            row.setDocId(scope.canonicalDocId);
            row.setChunkId(chunkId);
            row.setEnabled(enabled);
            knowledgeChunkCtrlMapper.insert(row);
        } else {
            existing.setEnabled(enabled);
            knowledgeChunkCtrlMapper.updateById(existing);
        }
        return true;
    }

    private Map<String, Object> buildDocumentItem(Long kbId, String sourceLocation, List<RagParentDO> parents, KnowledgeDocumentDO docRow) {
        if ((sourceLocation == null || sourceLocation.isBlank()) && docRow == null) {
            return null;
        }
        String canonicalDocId = resolveCanonicalDocId(parents, docRow);
        if (canonicalDocId == null || canonicalDocId.isBlank()) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docId", canonicalDocId);
        item.put("kbId", kbId);
        item.put("docName", resolveDocName(docRow, sourceLocation));
        item.put("status", Optional.ofNullable(docRow).map(KnowledgeDocumentDO::getStatus).filter(text -> !text.isBlank()).orElse("READY"));
        item.put("enabled", docRow == null || Boolean.TRUE.equals(docRow.getEnabled()));
        item.put("sourceType", Optional.ofNullable(docRow)
                .map(KnowledgeDocumentDO::getSourceType)
                .filter(text -> !text.isBlank())
                .orElseGet(() -> parents.stream()
                        .map(RagParentDO::getSourceType)
                        .filter(Objects::nonNull)
                        .filter(text -> !text.isBlank())
                        .findFirst()
                        .orElse("LOCAL_VAULT")));
        item.put("sourceLocation", sourceLocation == null ? Optional.ofNullable(docRow).map(KnowledgeDocumentDO::getSourceLocation).orElse("") : sourceLocation);
        item.put("chunkCount", parents.isEmpty()
                ? Optional.ofNullable(docRow).map(KnowledgeDocumentDO::getChunkCount).orElse(0)
                : countChunks(parents.stream().map(RagParentDO::getParentId).filter(Objects::nonNull).toList()));
        item.put("_sortTime", resolveSortTime(docRow, parents));
        item.put("_searchBlob", buildSearchBlob(docRow, parents, sourceLocation));
        return item;
    }

    private boolean matchesDocumentFilters(Map<String, Object> item, String status, String keyword) {
        if (status != null && !status.isBlank()) {
            String docStatus = String.valueOf(item.getOrDefault("status", ""));
            if (!status.equalsIgnoreCase(docStatus)) {
                return false;
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.toLowerCase(Locale.ROOT);
            String searchBlob = String.valueOf(item.getOrDefault("_searchBlob", "")).toLowerCase(Locale.ROOT);
            return searchBlob.contains(k);
        }
        return true;
    }

    private String buildSearchBlob(KnowledgeDocumentDO docRow, List<RagParentDO> parents, String sourceLocation) {
        List<String> parts = new ArrayList<>();
        if (docRow != null) {
            parts.add(docRow.getDocName());
            parts.add(docRow.getStatus());
            parts.add(docRow.getSourceType());
        }
        parts.add(sourceLocation);
        for (RagParentDO parent : parents) {
            parts.add(parent.getSectionPath());
            parts.add(parent.getKnowledgeTags());
            parts.add(parent.getSourceType());
        }
        return parts.stream().filter(Objects::nonNull).collect(Collectors.joining(" "));
    }

    private String resolveDocName(KnowledgeDocumentDO docRow, String sourceLocation) {
        if (docRow != null && docRow.getDocName() != null && !docRow.getDocName().isBlank()) {
            return docRow.getDocName();
        }
        if (sourceLocation == null || sourceLocation.isBlank()) {
            return "unknown.md";
        }
        int slash = Math.max(sourceLocation.lastIndexOf('/'), sourceLocation.lastIndexOf('\\'));
        return slash >= 0 ? sourceLocation.substring(slash + 1) : sourceLocation;
    }

    private int countChunks(List<String> parentIds) {
        List<String> validParentIds = parentIds == null ? List.of() : parentIds.stream()
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .distinct()
                .toList();
        if (validParentIds.isEmpty()) {
            return 0;
        }
        return ragChildMapper.selectCount(new LambdaQueryWrapper<RagChildDO>().in(RagChildDO::getParentId, validParentIds)).intValue();
    }

    private LocalDateTime resolveSortTime(KnowledgeDocumentDO docRow, List<RagParentDO> parents) {
        LocalDateTime docTime = docRow == null ? null : Optional.ofNullable(docRow.getUpdatedAt()).orElse(docRow.getCreatedAt());
        LocalDateTime parentTime = parents.stream()
                .map(parent -> Optional.ofNullable(parent.getUpdatedAt()).orElse(parent.getCreatedAt()))
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        if (docTime == null) {
            return parentTime;
        }
        if (parentTime == null) {
            return docTime;
        }
        return docTime.isAfter(parentTime) ? docTime : parentTime;
    }

    private String resolveCanonicalDocId(List<RagParentDO> parents, KnowledgeDocumentDO docRow) {
        Optional<String> parentId = parents.stream()
                .map(RagParentDO::getParentId)
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .sorted()
                .findFirst();
        if (parentId.isPresent()) {
            return parentId.get();
        }
        if (docRow != null && docRow.getId() != null) {
            return "docrow-" + docRow.getId();
        }
        return null;
    }

    private DocumentScope resolveDocumentScope(String docId) {
        if (docId == null || docId.isBlank()) {
            return null;
        }
        if (docId.startsWith("docrow-")) {
            try {
                Long rowId = Long.parseLong(docId.substring("docrow-".length()));
                KnowledgeDocumentDO docRow = knowledgeDocumentMapper.selectById(rowId);
                if (docRow == null || docRow.getSourceLocation() == null || docRow.getSourceLocation().isBlank()) {
                    return null;
                }
                List<RagParentDO> parents = ragParentMapper.selectList(
                        new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getFilePath, docRow.getSourceLocation())
                );
                String canonicalDocId = resolveCanonicalDocId(parents, docRow);
                if (canonicalDocId == null || canonicalDocId.isBlank()) {
                    canonicalDocId = docId;
                }
                return new DocumentScope(canonicalDocId, docRow.getSourceLocation(), parents);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        RagParentDO parent = ragParentMapper.selectOne(
                new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getParentId, docId).last("LIMIT 1")
        );
        if (parent == null || parent.getFilePath() == null || parent.getFilePath().isBlank()) {
            return null;
        }
        List<RagParentDO> parents = ragParentMapper.selectList(
                new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getFilePath, parent.getFilePath())
        );
        return new DocumentScope(resolveCanonicalDocId(parents, null), parent.getFilePath(), parents);
    }

    private static class DocumentScope {
        private final String canonicalDocId;
        private final String sourceLocation;
        private final List<RagParentDO> parents;
        private final List<String> parentIds;

        private DocumentScope(String canonicalDocId, String sourceLocation, List<RagParentDO> parents) {
            this.canonicalDocId = canonicalDocId;
            this.sourceLocation = sourceLocation;
            this.parents = parents == null ? List.of() : List.copyOf(parents);
            this.parentIds = this.parents.stream()
                    .map(RagParentDO::getParentId)
                    .filter(Objects::nonNull)
                    .filter(text -> !text.isBlank())
                    .distinct()
                    .toList();
        }
    }

    public boolean batchSetChunkEnabled(String docId, Map<String, Boolean> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Boolean> entry : payload.entrySet()) {
            setChunkEnabled(docId, entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
        }
        return true;
    }
}
