package com.example.interview.controller;

import com.example.interview.service.KnowledgeDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    public KnowledgeDocumentController(KnowledgeDocumentService knowledgeDocumentService) {
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<Map<String, Object>> pageDocuments(
            @PathVariable("kbId") Long kbId,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return ResponseEntity.ok(knowledgeDocumentService.pageDocuments(kbId, pageNo, pageSize, status, keyword));
    }

    @GetMapping("/knowledge-documents/{docId}")
    public ResponseEntity<?> getDocumentDetail(@PathVariable("docId") String docId) {
        return knowledgeDocumentService.getDocumentDetail(docId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/knowledge-documents/{docId}/chunks")
    public ResponseEntity<Map<String, Object>> pageChunks(
            @PathVariable("docId") String docId,
            @RequestParam(value = "current", required = false) Integer current,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "enabled", required = false) Boolean enabled
    ) {
        return ResponseEntity.ok(knowledgeDocumentService.pageChunks(docId, current, size, enabled));
    }

    @PatchMapping("/knowledge-documents/{docId}/enabled")
    public ResponseEntity<?> setDocumentEnabled(
            @PathVariable("docId") String docId,
            @RequestParam("value") boolean value
    ) {
        boolean ok = knowledgeDocumentService.setDocumentEnabled(docId, value);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of("message", "文档不存在"));
        }
        return ResponseEntity.ok(Map.of("message", "success"));
    }

    @DeleteMapping("/knowledge-documents/{docId}")
    public ResponseEntity<?> deleteDocument(@PathVariable("docId") String docId) {
        boolean ok = knowledgeDocumentService.deleteDocument(docId);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of("message", "删除失败或文档不存在"));
        }
        return ResponseEntity.ok(Map.of("message", "success"));
    }

    @PostMapping("/knowledge-documents/{docId}/rechunk")
    public ResponseEntity<?> rechunkDocument(@PathVariable("docId") String docId) {
        boolean ok = knowledgeDocumentService.rechunkDocument(docId);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of("message", "重建失败或文档不存在"));
        }
        return ResponseEntity.ok(Map.of("message", "success"));
    }

    @PatchMapping("/knowledge-documents/{docId}/chunks/{chunkId}/enabled")
    public ResponseEntity<?> setChunkEnabled(
            @PathVariable("docId") String docId,
            @PathVariable("chunkId") String chunkId,
            @RequestParam("value") boolean value
    ) {
        boolean ok = knowledgeDocumentService.setChunkEnabled(docId, chunkId, value);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of("message", "分块不存在"));
        }
        return ResponseEntity.ok(Map.of("message", "success"));
    }

    @PostMapping("/knowledge-documents/{docId}/chunks/batch-enabled")
    public ResponseEntity<?> batchSetChunkEnabled(
            @PathVariable("docId") String docId,
            @RequestBody Map<String, Boolean> payload
    ) {
        boolean ok = knowledgeDocumentService.batchSetChunkEnabled(docId, payload);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体为空"));
        }
        return ResponseEntity.ok(Map.of("message", "success"));
    }
}
