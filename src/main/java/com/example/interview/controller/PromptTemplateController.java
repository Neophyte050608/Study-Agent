package com.example.interview.controller;

import com.example.interview.entity.PromptTemplateDO;
import com.example.interview.service.PromptManager;
import com.example.interview.service.PromptTemplateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/prompts")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;
    private final PromptManager promptManager;

    public PromptTemplateController(PromptTemplateService promptTemplateService, PromptManager promptManager) {
        this.promptTemplateService = promptTemplateService;
        this.promptManager = promptManager;
    }

    @GetMapping
    public List<PromptTemplateDO> listAll(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type) {
        return promptTemplateService.listAll(category, type);
    }

    @GetMapping("/{name}")
    public PromptTemplateDO getByName(@PathVariable String name) {
        PromptTemplateDO template = promptTemplateService.getByName(name);
        if (template == null) {
            throw new RuntimeException("模板不存在: " + name);
        }
        return template;
    }

    @PostMapping
    public PromptTemplateDO create(@RequestBody Map<String, String> body) {
        return promptTemplateService.create(
                body.get("name"),
                body.get("category"),
                body.get("type"),
                body.get("title"),
                body.get("description"),
                body.get("content")
        );
    }

    @PutMapping("/{name}")
    public Map<String, String> update(@PathVariable String name,
                                      @RequestBody Map<String, String> body) {
        promptTemplateService.update(name,
                body.get("content"),
                body.get("title"),
                body.get("description"),
                body.get("category"));
        return Map.of("status", "success");
    }

    @DeleteMapping("/{name}")
    public Map<String, String> delete(@PathVariable String name) {
        promptTemplateService.delete(name);
        return Map.of("status", "success");
    }

    @PostMapping("/{name}/preview")
    public Map<String, String> preview(@PathVariable String name,
                                       @RequestBody(required = false) Map<String, Object> variables) {
        String rendered = promptTemplateService.preview(name, variables);
        return Map.of("rendered", rendered);
    }

    @PostMapping("/reload")
    public Map<String, String> reload() {
        promptManager.reloadCache();
        return Map.of("status", "success");
    }
}
