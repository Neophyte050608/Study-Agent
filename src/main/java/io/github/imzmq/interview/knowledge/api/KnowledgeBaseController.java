package io.github.imzmq.interview.knowledge.api;

import io.github.imzmq.interview.entity.knowledge.KnowledgeBaseDO;
import io.github.imzmq.interview.knowledge.application.catalog.KnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeBaseDO>> listKnowledgeBases() {
        return ResponseEntity.ok(knowledgeBaseService.listAll());
    }
}







