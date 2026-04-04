package com.example.interview.controller;

import com.example.interview.agent.CodingPracticeAgent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 编程练习控制器。
 */
@RestController
@RequestMapping("/api/coding")
public class CodingPracticeController {

    private final CodingPracticeAgent codingPracticeAgent;

    public CodingPracticeController(CodingPracticeAgent codingPracticeAgent) {
        this.codingPracticeAgent = codingPracticeAgent;
    }

    /**
     * 批量提交选择题结果。
     */
    @PostMapping("/batch-quiz/submit")
    public ResponseEntity<Map<String, Object>> submitBatchQuiz(@RequestBody Map<String, Object> body) {
        body.put("action", "batch-quiz-submit");
        return ResponseEntity.ok(codingPracticeAgent.execute(body));
    }
}
