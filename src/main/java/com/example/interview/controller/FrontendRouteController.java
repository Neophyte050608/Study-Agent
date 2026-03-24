package com.example.interview.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendRouteController {

    @GetMapping("/monitoring.html")
    public String redirectLegacyMonitoring() {
        return "redirect:/monitoring";
    }

    @GetMapping("/interview.html")
    public String redirectLegacyInterview() {
        return "redirect:/interview";
    }

    @GetMapping("/knowledge.html")
    public String redirectLegacyKnowledge() {
        return "redirect:/notes";
    }

    @GetMapping("/notes.html")
    public String redirectLegacyNotes() {
        return "redirect:/notes";
    }

    @GetMapping("/practice.html")
    public String redirectLegacyPractice() {
        return "redirect:/coding";
    }

    @GetMapping("/coding.html")
    public String redirectLegacyCoding() {
        return "redirect:/coding";
    }

    @GetMapping("/profile.html")
    public String redirectLegacyProfile() {
        return "redirect:/profile";
    }

    @GetMapping("/ops.html")
    public String redirectLegacyOps() {
        return "redirect:/ops";
    }

    @GetMapping("/settings.html")
    public String redirectLegacySettings() {
        return "redirect:/settings";
    }

    @GetMapping("/workspace.html")
    public String redirectLegacyWorkspace() {
        return "redirect:/workspace";
    }

    @GetMapping("/mcp.html")
    public String redirectLegacyMcp() {
        return "redirect:/mcp";
    }

    @GetMapping("/intent-tree.html")
    public String redirectLegacyIntentTree() {
        return "redirect:/intent-tree";
    }

    @GetMapping({
            "/",
            "/monitoring",
            "/monitoring/**",
            "/notes",
            "/notes/**",
            "/coding",
            "/coding/**",
            "/profile",
            "/profile/**",
            "/interview",
            "/interview/**",
            "/knowledge",
            "/knowledge/**",
            "/practice",
            "/practice/**",
            "/ops",
            "/ops/**",
            "/settings",
            "/settings/**",
            "/workspace",
            "/workspace/**",
            "/mcp",
            "/mcp/**",
            "/intent-tree",
            "/intent-tree/**",
            "/app",
            "/app/**"
    })
    public String forwardToSpa() {
        return "forward:/spa/index.html";
    }
}
