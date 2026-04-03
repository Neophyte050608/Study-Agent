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

import java.io.File;
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
        List<RagParentDO> all = ragParentMapper.selectList(new LambdaQueryWrapper<>());
        List<RagParentDO> filtered = all.stream().filter(item -> {
            if (status != null && !status.isBlank() && !"READY".equalsIgnoreCase(status)) {
                return false;
            }
            if (keyword != null && !keyword.isBlank()) {
                String k = keyword.toLowerCase(Locale.ROOT);
                String fp = Optional.ofNullable(item.getFilePath()).orElse("").toLowerCase(Locale.ROOT);
                String sp = Optional.ofNullable(item.getSectionPath()).orElse("").toLowerCase(Locale.ROOT);
                String tags = Optional.ofNullable(item.getKnowledgeTags()).orElse("").toLowerCase(Locale.ROOT);
                return fp.contains(k) || sp.contains(k) || tags.contains(k);
            }
            return true;
        }).collect(Collectors.toList());
        int total = filtered.size();
        int from = Math.max(0, (p - 1) * s);
        int to = Math.min(total, from + s);
        List<RagParentDO> pageItems = from >= to ? List.of() : filtered.subList(from, to);

        List<Map<String, Object>> records = new ArrayList<>();
        for (RagParentDO parent : pageItems) {
            int chunkCount = ragChildMapper.selectCount(new LambdaQueryWrapper<RagChildDO>()
                    .eq(RagChildDO::getParentId, parent.getParentId())).intValue();
            KnowledgeDocumentDO docRow = knowledgeDocumentMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>().eq(KnowledgeDocumentDO::getSourceLocation, parent.getFilePath()).last("LIMIT 1")
            );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", parent.getParentId());
            item.put("kbId", kbId == null ? 1L : kbId);
            item.put("docName", Optional.ofNullable(parent.getSectionPath()).filter(sx -> !sx.isBlank())
                    .orElseGet(() -> Optional.ofNullable(parent.getFilePath()).map(fp -> {
                        int idx = fp.lastIndexOf(File.separatorChar);
                        return idx >= 0 ? fp.substring(idx + 1) : fp;
                    }).orElse("unknown.md")));
            item.put("status", "READY");
            item.put("enabled", docRow == null || Boolean.TRUE.equals(docRow.getEnabled()));
            item.put("sourceType", Optional.ofNullable(parent.getSourceType()).orElse("LOCAL_VAULT"));
            item.put("sourceLocation", parent.getFilePath());
            item.put("chunkCount", chunkCount);
            records.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", p);
        result.put("size", s);
        result.put("total", total);
        result.put("records", records);
        return result;
    }

    public Optional<Map<String, Object>> getDocumentDetail(String docId) {
        List<RagParentDO> list = ragParentMapper.selectList(new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getParentId, docId));
        if (list.isEmpty()) {
            return Optional.empty();
        }
        RagParentDO parent = list.get(0);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docId", parent.getParentId());
        item.put("docName", Optional.ofNullable(parent.getSectionPath()).orElse("unknown.md"));
        item.put("sourceType", Optional.ofNullable(parent.getSourceType()).orElse("LOCAL_VAULT"));
        item.put("sourceLocation", parent.getFilePath());
        item.put("knowledgeTags", Optional.ofNullable(parent.getKnowledgeTags()).orElse(""));
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
        List<RagChildDO> all = ragChildMapper.selectList(new LambdaQueryWrapper<RagChildDO>().eq(RagChildDO::getParentId, docId));
        int total = all.size();
        int from = Math.max(0, (p - 1) * s);
        int to = Math.min(total, from + s);
        List<RagChildDO> pageItems = from >= to ? List.of() : all.subList(from, to);
        List<Map<String, Object>> records = new ArrayList<>();
        for (RagChildDO child : pageItems) {
            KnowledgeChunkCtrlDO ctrl = knowledgeChunkCtrlMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeChunkCtrlDO>()
                            .eq(KnowledgeChunkCtrlDO::getDocId, docId)
                            .eq(KnowledgeChunkCtrlDO::getChunkId, child.getChildId())
                            .last("LIMIT 1")
            );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", child.getChildId());
            item.put("childIndex", child.getChildIndex());
            item.put("enabled", ctrl == null || Boolean.TRUE.equals(ctrl.getEnabled()));
            item.put("snippet", Optional.ofNullable(child.getChildText()).map(txt -> txt.length() > 200 ? txt.substring(0, 200) + "..." : txt).orElse(""));
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
        List<RagParentDO> parents = ragParentMapper.selectList(new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getParentId, docId));
        if (parents.isEmpty()) {
            return false;
        }
        RagParentDO parent = parents.get(0);
        KnowledgeDocumentDO existing = knowledgeDocumentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentDO>().eq(KnowledgeDocumentDO::getSourceLocation, parent.getFilePath()).last("LIMIT 1")
        );
        if (existing == null) {
            KnowledgeDocumentDO row = new KnowledgeDocumentDO();
            row.setKbId(1L);
            row.setDocName(Optional.ofNullable(parent.getSectionPath()).orElse("unknown.md"));
            row.setStatus("READY");
            row.setEnabled(enabled);
            row.setSourceType(Optional.ofNullable(parent.getSourceType()).orElse("LOCAL_VAULT"));
            row.setSourceLocation(parent.getFilePath());
            row.setChunkCount(ragChildMapper.selectCount(new LambdaQueryWrapper<RagChildDO>().eq(RagChildDO::getParentId, docId)).intValue());
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
        List<RagParentDO> parents = ragParentMapper.selectList(new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getParentId, docId));
        if (parents.isEmpty()) {
            return false;
        }
        String filePath = parents.get(0).getFilePath();
        boolean ok = ingestionService.deleteByFilePath(filePath);
        knowledgeDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentDO>().eq(KnowledgeDocumentDO::getSourceLocation, filePath));
        return ok;
    }

    public boolean rechunkDocument(String docId) {
        if (ingestionTaskService.hasRunningTasks()) {
            return false;
        }
        List<RagParentDO> parents = ragParentMapper.selectList(new LambdaQueryWrapper<RagParentDO>().eq(RagParentDO::getParentId, docId));
        if (parents.isEmpty()) {
            return false;
        }
        String filePath = parents.get(0).getFilePath();
        return ingestionService.rechunkByFilePath(filePath);
    }

    public boolean setChunkEnabled(String docId, String chunkId, boolean enabled) {
        KnowledgeChunkCtrlDO existing = knowledgeChunkCtrlMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeChunkCtrlDO>()
                        .eq(KnowledgeChunkCtrlDO::getDocId, docId)
                        .eq(KnowledgeChunkCtrlDO::getChunkId, chunkId)
                        .last("LIMIT 1")
        );
        if (existing == null) {
            KnowledgeChunkCtrlDO row = new KnowledgeChunkCtrlDO();
            row.setDocId(docId);
            row.setChunkId(chunkId);
            row.setEnabled(enabled);
            knowledgeChunkCtrlMapper.insert(row);
        } else {
            existing.setEnabled(enabled);
            knowledgeChunkCtrlMapper.updateById(existing);
        }
        return true;
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
