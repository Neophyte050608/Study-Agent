package io.github.imzmq.interview.knowledge.api;

import io.github.imzmq.interview.knowledge.application.indexing.LocalKnowledgeIndexBuildService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-retrieval")
public class KnowledgeRetrievalController {

    private final LocalKnowledgeIndexBuildService localKnowledgeIndexBuildService;

    public KnowledgeRetrievalController(LocalKnowledgeIndexBuildService localKnowledgeIndexBuildService) {
        this.localKnowledgeIndexBuildService = localKnowledgeIndexBuildService;
    }

    @GetMapping("/index/status")
    public ResponseEntity<LocalKnowledgeIndexBuildService.IndexStatus> getIndexStatus() {
        return ResponseEntity.ok(localKnowledgeIndexBuildService.currentStatus());
    }

    @PostMapping("/index/build")
    public ResponseEntity<?> buildIndex(@RequestBody(required = false) BuildIndexRequest request) {
        try {
            LocalKnowledgeIndexBuildService.IndexBuildResult result = localKnowledgeIndexBuildService.build(
                    new LocalKnowledgeIndexBuildService.IndexBuildRequest(
                            request == null ? "" : safe(request.vaultPath()),
                            request == null ? "" : safe(request.outputPath()),
                            request == null ? List.of() : safeList(request.ignoreDirs()),
                            request == null ? List.of() : safeList(request.includeDirs()),
                            request == null ? List.of() : safeList(request.excludeDirs()),
                            request == null ? "" : safe(request.scopeFilePath()),
                            request != null && Boolean.TRUE.equals(request.activate())
                    )
            );
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "message", e.getMessage() == null ? "构建本地知识索引失败" : e.getMessage()
            ));
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    public record BuildIndexRequest(String vaultPath,
                                    String outputPath,
                                    List<String> ignoreDirs,
                                    List<String> includeDirs,
                                    List<String> excludeDirs,
                                    String scopeFilePath,
                                    Boolean activate) {
    }
}






