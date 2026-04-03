package com.example.interview.controller;

import com.example.interview.entity.KnowledgeBaseDO;
import com.example.interview.service.KnowledgeBaseService;
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
